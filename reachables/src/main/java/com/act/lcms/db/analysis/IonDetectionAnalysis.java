package com.act.lcms.db.analysis;

import com.act.lcms.MS1;
import com.act.lcms.MassCalculator;
import com.act.lcms.XZ;
import com.act.lcms.db.io.DB;
import com.act.lcms.db.io.LoadPlateCompositionIntoDB;
import com.act.lcms.db.io.report.IonAnalysisInterchangeModel;
import com.act.lcms.db.model.LCMSWell;
import com.act.lcms.db.model.Plate;
import com.act.lcms.db.model.PlateWell;
import com.act.lcms.db.model.ScanFile;
import com.act.utils.TSVParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

public class IonDetectionAnalysis {

  private static final Logger LOGGER = LogManager.getFormatterLogger(IonDetectionAnalysis.class);
  private static final boolean USE_SNR_FOR_LCMS_ANALYSIS = true;
  private static final boolean USE_FINE_GRAINED_TOLERANCE = false;
  private static final String DEFAULT_ION = "M+H";
  private static final Double MIN_SNR_THRESHOLD = 1000.0;
  private static final Double MIN_TIME_THRESHOLD = 15.0;
  private static final String OPTION_LCMS_FILE_DIRECTORY = "d";
  private static final String OPTION_INPUT_PREDICTION_CORPUS = "sc";
  private static final String OPTION_OUTPUT_PREFIX = "o";
  private static final String OPTION_PLOTTING_DIR = "p";
  private static final String OPTION_INCLUDE_IONS = "i";
  private static final String OPTION_MIN_THRESHOLD = "f";
  private static final String OPTION_INPUT_POSITIVE_AND_NEGATIVE_CONTROL_WELLS_FILE = "t";
  private static final String HEADER_WELL_TYPE = "WELL_TYPE";
  private static final String HEADER_WELL_ROW = "WELL_ROW";
  private static final String HEADER_WELL_COLUMN = "WELL_COLUMN";
  private static final String HEADER_PLATE_BARCODE = "PLATE_BARCODE";
  private static final Set<String> ALL_HEADERS =
      new HashSet<>(Arrays.asList(HEADER_WELL_TYPE, HEADER_WELL_ROW, HEADER_WELL_COLUMN, HEADER_PLATE_BARCODE));
  private static Double PROGRESS = 0.0;

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class takes as input an experimental setup containing positive wells and negative control well. Along with this " +
      "the class also takes in a list of chemicals to be validated by the LCMS analysis. It then performs SNR analysis " +
      "to detect which chemicals are strongly represented in the LCMS analysis and outputs those in a json file per positive sample"
  }, "");
  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_LCMS_FILE_DIRECTORY)
        .argName("directory")
        .desc("The directory where LCMS analysis results live")
        .hasArg().required()
        .longOpt("data-dir")
    );
    // The OPTION_INPUT_PREDICTION_CORPUS file is a json formatted file that is serialized from the class "L2PredictionCorpus"
    add(Option.builder(OPTION_INPUT_PREDICTION_CORPUS)
        .argName("input prediction corpus")
        .desc("The input prediction corpus")
        .hasArg().required()
        .longOpt("prediction-corpus")
    );
    add(Option.builder(OPTION_OUTPUT_PREFIX)
        .argName("output prefix")
        .desc("A prefix for the output data/pdf files")
        .hasArg().required()
        .longOpt("output-prefix")
    );
    add(Option.builder(OPTION_PLOTTING_DIR)
        .argName("plotting directory")
        .desc("The absolute path of the plotting directory")
        .hasArg().required()
        .longOpt("plotting-dir")
    );
    add(Option.builder(OPTION_INCLUDE_IONS)
        .argName("ion list")
        .desc("A comma-separated list of ions to include in the search (ions not in this list will be ignored)")
        .hasArgs().valueSeparator(',')
        .longOpt("include-ions")
    );
    add(Option.builder(OPTION_MIN_THRESHOLD)
        .argName("min threshold")
        .desc("The min threshold")
        .hasArg()
        .longOpt("min-threshold")
    );
    // This input file is structured as a tsv file with the following schema:
    //    WELL_TYPE  PLATE_BARCODE  WELL_ROW  WELL_COLUMN
    // eg.   POS        12389        0           1
    add(Option.builder(OPTION_INPUT_POSITIVE_AND_NEGATIVE_CONTROL_WELLS_FILE)
        .argName("input positive and negative control wells")
        .desc("A tsv file containing positive and negative wells")
        .hasArg().required()
        .longOpt("input-positive-negative-control-wells")
    );
  }};

  static {
    // Add DB connection options.
    OPTION_BUILDERS.addAll(DB.DB_OPTION_BUILDERS);
  }

  public static class ChemicalAndIon {
    private String chemical;
    private String ion;

    public ChemicalAndIon(String chemical, String ion) {
      this.chemical = chemical;
      this.ion = ion;
    }

    public String getIon() {
      return ion;
    }

    public void setIon(String ion) {
      this.ion = ion;
    }

    public String getChemical() {
      return chemical;
    }

    public void setChemical(String chemical) {
      this.chemical = chemical;
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof ChemicalAndIon)) return false;
      ChemicalAndIon that = (ChemicalAndIon) o;
      return this.chemical.equals(that.getChemical()) && this.getIon().equals(that.getIon());
    }

    @Override
    public int hashCode() {
      int result = this.chemical != null ? this.chemical.hashCode() : 0;
      result = 31 * result + (this.ion != null ? this.ion.hashCode() : 0);
      return result;
    }
  }

  /**
   * This function takes a positive well, associated negative control wells and target mass charge values and returns
   * the peak intensity, time and snr values for each mass charge in a map.
   * @param lcmsDir The dir where the lcms data live
   * @param db The db used to extract scan files
   * @param positiveWell The positive LCMS well under analysis
   * @param negativeWells The negative LCMS wells to control against
   * @param plateCache The plate cache
   * @param searchMZs A list of mass charge values to validate
   * @param plottingDir The plotting directory
   * @param <T> The platewell abstraction
   * @return A mapping of chemical to ion to intensity, time and max peak values
   * @throws Exception
   */
  public static <T extends PlateWell<T>> Map<String, Pair<String, Pair<XZ, Double>>> getSnrResultsAndPlotDiagnosticsForEachMoleculeAndItsMetlinIon(
      File lcmsDir, DB db, T positiveWell, List<T> negativeWells, HashMap<Integer, Plate> plateCache, List<Pair<String, Double>> searchMZs,
      String plottingDir) throws Exception {

    Plate plate = plateCache.get(positiveWell.getPlateId());
    if (plate == null) {
      plate = Plate.getPlateById(db, positiveWell.getPlateId());
      plateCache.put(plate.getId(), plate);
    }

    LOGGER.info("Reading scan data for positive well");

    ChemicalToMapOfMetlinIonsToIntensityTimeValues positiveWellSignalProfiles = AnalysisHelper.readScanData(
        db,
        lcmsDir,
        searchMZs,
        ScanData.KIND.POS_SAMPLE,
        plateCache,
        positiveWell,
        USE_FINE_GRAINED_TOLERANCE,
        USE_SNR_FOR_LCMS_ANALYSIS);

    if (positiveWellSignalProfiles == null) {
      LOGGER.error("No positive data available.");
      System.exit(1);
    }

    PROGRESS += 50.0/(1.0 + negativeWells.size());
    printProgress(PROGRESS);

    List<ChemicalToMapOfMetlinIonsToIntensityTimeValues> negativeWellsSignalProfiles = new ArrayList<>();

    LOGGER.info("The number of peak negatives to process are %d", negativeWellsSignalProfiles.size());

    for (T well : negativeWells) {

      LOGGER.info("Reading scan data for negative well");

      ChemicalToMapOfMetlinIonsToIntensityTimeValues peakDataNeg = AnalysisHelper.readScanData(
          db,
          lcmsDir,
          searchMZs,
          ScanData.KIND.NEG_CONTROL,
          plateCache,
          well,
          USE_FINE_GRAINED_TOLERANCE,
          USE_SNR_FOR_LCMS_ANALYSIS);

      if (peakDataNeg == null) {
        LOGGER.info("Peak negative analysis was null");
      }

      negativeWellsSignalProfiles.add(peakDataNeg);
      PROGRESS += 50.0/(1.0 + negativeWells.size());
      printProgress(PROGRESS);
    }

    Map<String, Pair<XZ, Double>> snrResults =
        WaveformAnalysis.performSNRAnalysisAndReturnMetlinIonsRankOrderedBySNRForNormalWells(
            positiveWellSignalProfiles, negativeWellsSignalProfiles, searchMZs);

    List<T> allWells = new ArrayList<>();
    allWells.add(positiveWell);
    allWells.addAll(negativeWells);

    // This variable is used as a part of the file path dir to uniquely identify the pos/neg wells for the chemical.
    StringBuilder indexedPath = new StringBuilder();
    for (T well : allWells) {
      indexedPath.append(Integer.toString(well.getId()) + "-");
    }

    Map<String, String> plottingFileMappings =
        ChemicalToMapOfMetlinIonsToIntensityTimeValues.plotPositiveAndNegativeControlsForEachMZ(
            searchMZs, indexedPath.toString(), positiveWellSignalProfiles, negativeWellsSignalProfiles, plottingDir);

    Map<String, Pair<String, Pair<XZ, Double>>> mzToPlotDirAndSNR = new HashMap<>();
    for (Map.Entry<String, Pair<XZ, Double>> entry : snrResults.entrySet()) {
      String plottingPath = plottingFileMappings.get(entry.getKey());
      XZ snr = entry.getValue().getLeft();

      if (plottingDir == null || snr == null) {
        System.err.format("Plotting directory or snr is null\n");
        System.exit(1);
      }

      mzToPlotDirAndSNR.put(entry.getKey(), Pair.of(plottingPath, entry.getValue()));
    }

    return mzToPlotDirAndSNR;
  }

  public static void printProgress(Double progressStatus) {
    LOGGER.info("Progress: %d", progressStatus);
  }

  public static void main(String[] args) throws Exception {

    Options opts = new Options();
    for (Option.Builder b : OPTION_BUILDERS) {
      opts.addOption(b.build());
    }

    CommandLine cl = null;
    try {
      CommandLineParser parser = new DefaultParser();
      cl = parser.parse(opts, args);
    } catch (ParseException e) {
      System.err.format("Argument parsing failed: %s\n", e.getMessage());
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      return;
    }

    File lcmsDir = new File(cl.getOptionValue(OPTION_LCMS_FILE_DIRECTORY));
    if (!lcmsDir.isDirectory()) {
      System.err.format("File at %s is not a directory\n", lcmsDir.getAbsolutePath());
      HELP_FORMATTER.printHelp(LoadPlateCompositionIntoDB.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    String plottingDirectory = cl.getOptionValue(OPTION_PLOTTING_DIR);

    // Get include and excluse ions from command line
    Set<String> includeIons;
    if (cl.hasOption(OPTION_INCLUDE_IONS)) {
      String[] ionNames = cl.getOptionValues(OPTION_INCLUDE_IONS);
      includeIons = new HashSet<>(Arrays.asList(ionNames));
      LOGGER.info("Including ions in search: %s\n", StringUtils.join(includeIons, ", "));
    } else {
      includeIons = new HashSet<>();
      includeIons.add(DEFAULT_ION);
    }

    // Read product inchis from the prediction corpus
    File inputPredictionCorpus = new File(cl.getOptionValue(OPTION_INPUT_PREDICTION_CORPUS));
    //L2PredictionCorpus predictionCorpus = L2PredictionCorpus.readPredictionsFromJsonFile(inputPredictionCorpus);

    List<Pair<String, Double>> searchMZs = new ArrayList<>();
    Map<Double, Set<ChemicalAndIon>> massChargeToChemicalAndIon = new HashMap<>();

    // Construct BufferedReader from FileReader
    BufferedReader br = new BufferedReader(new FileReader(inputPredictionCorpus));

    // Get the inchis from input file
    String product = null;
    while ((product = br.readLine()) != null) {
      product = product.replace("\n", "");
      try {
        // Assume the ion modes are all positive!
        Map<String, Double> allMasses = MS1.getIonMasses(MassCalculator.calculateMass(product), MS1.IonMode.POS);
        Map<String, Double> metlinMasses = Utils.filterMasses(allMasses, includeIons, null);

        for (Map.Entry<String, Double> entry : metlinMasses.entrySet()) {
          Set<ChemicalAndIon> res = massChargeToChemicalAndIon.get(entry.getValue());
          if (res == null) {
            res = new HashSet<>();
            massChargeToChemicalAndIon.put(entry.getValue(), res);
          }

          ChemicalAndIon chemicalAndIon = new ChemicalAndIon(product, entry.getKey());
          res.add(chemicalAndIon);
        }
      } catch (Exception e) {
        continue;
      }
    }

    br.close();

    // Make a mapping between fake chemical name and mass charge to de-duplicate against.
    Integer chemicalCounter = 0;
    Map<String, Double> chemIDToMassCharge = new HashMap<>();
    for (Double massCharge : massChargeToChemicalAndIon.keySet()) {
      String chemID = "CHEM_" + chemicalCounter.toString();
      chemIDToMassCharge.put(chemID, massCharge);
      searchMZs.add(Pair.of(chemID, massCharge));
      chemicalCounter++;
    }

    LOGGER.info("The number of mass charges are: %d", searchMZs.size());

    Double intensityThreshold = Double.parseDouble(cl.getOptionValue(OPTION_MIN_THRESHOLD));

    try (DB db = DB.openDBFromCLI(cl)) {
      ScanFile.insertOrUpdateScanFilesInDirectory(db, lcmsDir);

      // Get experimental setup ie. positive and negative wells from config file
      List<LCMSWell> positiveWells = new ArrayList<>();
      List<LCMSWell> negativeWells = new ArrayList<>();

      TSVParser parser = new TSVParser();
      parser.parse(new File(cl.getOptionValue(OPTION_INPUT_POSITIVE_AND_NEGATIVE_CONTROL_WELLS_FILE)));
      Set<String> headerSet = new HashSet<>(parser.getHeader());

      if (!headerSet.equals(ALL_HEADERS)) {
        LOGGER.error("Invalid header type");
        System.exit(1);
      }

      for (Map<String, String> row : parser.getResults()) {
        String wellType = row.get(HEADER_WELL_TYPE);
        String barcode = row.get(HEADER_PLATE_BARCODE);
        Integer rowCoordinate = Integer.parseInt(row.get(HEADER_WELL_ROW));
        Integer columnCoordinate = Integer.parseInt(row.get(HEADER_WELL_COLUMN));
        Plate queryPlate = Plate.getPlateByBarcode(db, barcode);
        LCMSWell well = LCMSWell.getInstance().getByPlateIdAndCoordinates(db, queryPlate.getId(), rowCoordinate, columnCoordinate);
        if (wellType.equals("POS")) {
          positiveWells.add(well);
        } else {
          negativeWells.add(well);
        }
      }

      LOGGER.info("Number of positive wells is: %d", positiveWells.size());
      LOGGER.info("Number of negative wells is: %d", negativeWells.size());

      HashMap<Integer, Plate> plateCache = new HashMap<>();
      String outputPrefix = cl.getOptionValue(OPTION_OUTPUT_PREFIX);

      List<List<IonAnalysisInterchangeModel.ResultForMZ>> allExperimentalResults = new ArrayList<>();

      for (LCMSWell positiveWell : positiveWells) {
        List<IonAnalysisInterchangeModel.ResultForMZ> experimentalResults = new ArrayList<>();
        String outAnalysis = outputPrefix + "_" + positiveWell.getId().toString() + ".json";

        LOGGER.info("Performing SNR analysis");
        Map<String, Pair<String, Pair<XZ, Double>>> result =
            getSnrResultsAndPlotDiagnosticsForEachMoleculeAndItsMetlinIon(
                lcmsDir,
                db,
                positiveWell,
                negativeWells,
                plateCache,
                searchMZs,
                plottingDirectory);

        for (Map.Entry<String, Pair<String, Pair<XZ, Double>>> mzToPlotAndSnr : result.entrySet()) {
          Double massCharge = chemIDToMassCharge.get(mzToPlotAndSnr.getKey());
          String plot = mzToPlotAndSnr.getValue().getLeft();
          Double intensity = mzToPlotAndSnr.getValue().getRight().getLeft().getIntensity();
          Double time = mzToPlotAndSnr.getValue().getRight().getLeft().getTime();
          Double snr = mzToPlotAndSnr.getValue().getRight().getRight();

          IonAnalysisInterchangeModel.ResultForMZ resultForMZ = new IonAnalysisInterchangeModel.ResultForMZ(massCharge);

          if (intensity > intensityThreshold &&
              time > MIN_TIME_THRESHOLD &&
              snr > MIN_SNR_THRESHOLD) {
            resultForMZ.setIsValid(true);
          } else {
            resultForMZ.setIsValid(false);
          }

          Set<ChemicalAndIon> inchisAndIon = massChargeToChemicalAndIon.get(massCharge);
          for (ChemicalAndIon pair : inchisAndIon) {
            String inchi = pair.getChemical();
            String ion = pair.getIon();
            IonAnalysisInterchangeModel.HitOrMiss hitOrMiss = new IonAnalysisInterchangeModel.HitOrMiss(inchi, ion, snr, time, intensity, plot);
            resultForMZ.addMolecule(hitOrMiss);
          }

          experimentalResults.add(resultForMZ);
        }

        IonAnalysisInterchangeModel ionAnalysisInterchangeModel = new IonAnalysisInterchangeModel(experimentalResults);
        ionAnalysisInterchangeModel.writeToJsonFile(new File(outAnalysis));
        allExperimentalResults.add(experimentalResults);
      }

      if (positiveWells.size() > 1) {
        // Post process analysis
        String outAnalysis = outputPrefix + "_post_process.json";
        List<IonAnalysisInterchangeModel.ResultForMZ> experimentalResults = new ArrayList<>();
        for (int i = 0; i < allExperimentalResults.get(0).size(); i++) {
          IonAnalysisInterchangeModel.ResultForMZ rep = allExperimentalResults.get(0).get(i);
          IonAnalysisInterchangeModel.ResultForMZ resultForMZ = new IonAnalysisInterchangeModel.ResultForMZ(rep.getMz());
          Boolean areAllValid = true;

          for (List<IonAnalysisInterchangeModel.ResultForMZ> res : allExperimentalResults) {
            if (!res.get(i).getIsValid()) {
              areAllValid = false;
            }
            resultForMZ.addMolecules(res.get(i).getMolecules());
          }

          resultForMZ.setIsValid(areAllValid);
          experimentalResults.add(resultForMZ);
        }

        IonAnalysisInterchangeModel model = new IonAnalysisInterchangeModel(experimentalResults);
        model.writeToJsonFile(new File(outAnalysis));
      }
    }
  }
}