package act.installer.reachablesexplorer;


import act.server.MongoDB;
import act.shared.Chemical;
import chemaxon.formats.MolExporter;
import chemaxon.formats.MolFormatException;
import chemaxon.struc.Molecule;
import com.act.analysis.chemicals.molecules.MoleculeExporter;
import com.act.analysis.chemicals.molecules.MoleculeFormat;
import com.act.analysis.chemicals.molecules.MoleculeImporter;
import com.act.biointerpretation.l2expansion.L2InchiCorpus;
import com.act.biointerpretation.mechanisminspection.ReactionRenderer;
import com.act.jobs.FileChecker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBObject;
import com.mongodb.MongoClient;
import com.mongodb.ServerAddress;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONException;
import org.json.JSONObject;
import org.mongojack.JacksonDBCollection;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Loader {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Logger LOGGER = LogManager.getFormatterLogger(Loader.class);

  private static final String ASSETS_LOCATION = "/Volumes/data-level1/data/reachables-explorer-rendering-cache";
  private static final String PNG_EXTENSION = ".png";
  private static final String DATABASE_BING_ONLY_HOST = "localhost";
  private static final String DATABASE_BING_ONLY_PORT = "27017";

  private MongoDB db;
  private WordCloudGenerator wcGenerator;
  private ReactionRenderer renderer;
  private DBCollection reachablesCollection;
  private JacksonDBCollection<Reachable, String> jacksonReachablesCollection;
  private L2InchiCorpus inchiCorpus;

  public Loader() throws UnknownHostException {
    db = new MongoDB("localhost", 27017, "validator_profiling_2");
    wcGenerator = new WordCloudGenerator(DATABASE_BING_ONLY_HOST, DATABASE_BING_ONLY_PORT);
    renderer = new ReactionRenderer();

    MongoClient mongoClient = new MongoClient(new ServerAddress("localhost", 27017));
    DB reachables = mongoClient.getDB("wiki_reachables");
    reachablesCollection = reachables.getCollection("test");
    jacksonReachablesCollection = JacksonDBCollection.wrap(reachablesCollection, Reachable.class, String.class);
  }

  private String getSmiles(Molecule mol) {
    try {
      return MoleculeExporter.exportMolecule(mol, MoleculeFormat.smiles$.MODULE$);
    } catch (Exception e) {
      return null;
    }
  }

  private String getInchiKey(Molecule mol) {
    try {
      // TODO: add inchi key the Michael's Molecule Exporter
      return MolExporter.exportToFormat(mol, "inchikey");
    } catch (Exception e) {
      return null;
    }
  }

  private String getPageName(String chemaxonTraditionalName, List<String> brendaNames, String inchi) {
    if (chemaxonTraditionalName == null || chemaxonTraditionalName.length() > 50) {
      brendaNames.sort((n1, n2) -> Integer.compare(n1.length(), n2.length()));
      if (brendaNames.size() == 0) {
        return inchi;
      } else {
        return brendaNames.get(0);
      }
    }
    return chemaxonTraditionalName;
  }

  private String getChemaxonTraditionalName(Molecule mol) {
    try {
      return MolExporter.exportToFormat(mol, "name:t");
    } catch (IOException e) {
      return null;
    }
  }

  public Reachable constructReachable(String inchi) throws IOException {
    // Only construct a new one if one doesn't already exist.
    Reachable preconstructedReachable = queryByInchi(inchi);
    if (preconstructedReachable != null) {
      return preconstructedReachable;
    }
    Molecule mol;
    try {
      mol = MoleculeImporter.importMolecule(inchi);
    } catch (MolFormatException e) {
      // TODO: add logging
      return null;
    }

    Chemical c = db.getChemicalFromInChI(inchi);
    List<String> names = c != null ? c.getBrendaNames() : Collections.emptyList();

    String smiles = getSmiles(mol);
    if (smiles == null) {
      LOGGER.error("Failed to export molecule %s to smiles", inchi);
    }

    String inchikey = getInchiKey(mol);
    if (inchikey == null) {
      LOGGER.error("Failed to export molecule %s to inchi key", inchi);
    }

    String chemaxonTraditionalName = getChemaxonTraditionalName(mol);
    if (chemaxonTraditionalName == null) {
      LOGGER.error("Failed to export molecule %s to traditional name", inchi);
    }

    String pageName = getPageName(chemaxonTraditionalName, names, inchi);
    return new Reachable(pageName, inchi, smiles, inchikey, names);
  }

  public void updateReachableWithWordcloud(Reachable reachable) throws IOException {
    File wordcloud = wcGenerator.generateWordCloud(reachable.getInchi());
    reachable.setWordCloudFilename(wordcloud.getName());
    upsert(reachable);
  }

  public void updateReachableWithRendering(Reachable reachable) {
    String renderingFilename = MoleculeRenderer.generateRendering(reachable.getInchi());
    reachable.setStructureFilename(renderingFilename);
    upsert(reachable);
  }

  public void loadReachables(File inchiFile) throws IOException {
    inchiCorpus = new L2InchiCorpus();
    inchiCorpus.loadCorpus(inchiFile);
    List<String> inchis = inchiCorpus.getInchiList();
    Reachable reachable;
    for (String inchi : inchis) {
      reachable = constructReachable(inchi);
      System.out.println(MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(reachable));
      // TODO: change the following to update the database maybe?
      jacksonReachablesCollection.insert(reachable);
    }

  }
  public void updateWithPrecursorData(String inchi, PrecursorData precursorData) {
    // TODO: is there a better way to perform the update? probably!
    Reachable reachable = jacksonReachablesCollection.findOne(new BasicDBObject("inchi", inchi));
    Reachable reachableOld = jacksonReachablesCollection.findOne(new BasicDBObject("inchi", inchi));
    if (reachable != null) {
      reachable.setPrecursorData(precursorData);
      jacksonReachablesCollection.update(reachableOld, reachable);
    }


    //DBUpdate.Builder builder = new DBUpdate.Builder();
    //builder.set("precursor", precursorData);
    // jacksonReachablesCollection.update(new BasicDBObject("inchi", inchi), builder);
  }

  public void updateWithPrecursor(String inchi, Precursor pre) throws IOException {
    Reachable reachable = queryByInchi(inchi);

    // If is null we create a new one
    reachable = reachable == null ? constructReachable(inchi) : reachable;
    reachable.getPrecursorData().addPrecursor(pre);

    upsert(reachable);
  }

  private Reachable queryByInchi(String inchi){
    DBObject query = new BasicDBObject("inchi", inchi);
    return jacksonReachablesCollection.findOne(query);
  }

  public void upsert(Reachable reachable){
    Reachable reachableOld = queryByInchi(reachable.getInchi());

    if (reachableOld != null) {
      LOGGER.info("Found previous reachable at InChI " + reachable.getInchi() + ".  Adding additional precursors to it.");
      jacksonReachablesCollection.update(reachableOld, reachable);
    } else {
      LOGGER.info("Did not find InChI " + reachable.getInchi() + " in database.  Creating a new reachable.");
      jacksonReachablesCollection.insert(reachable);
    }
  }

  public void updateFromReachablesFile(File file){
    MongoDB connection = new MongoDB("localhost", 27017, "validator_profiling_2");

    try {
      // Read in the file and parse it as JSON
      String jsonTxt = IOUtils.toString(new FileInputStream(file));
      JSONObject fileContents = new JSONObject(jsonTxt);
      // Parsing errors should happen as near to the point of loading as possible.
      Long parentId = fileContents.getLong("parent");
      Long currentId = fileContents.getLong("chemid");

      // Get the parent chemicals from the database.  JSON file contains ID.
      // We want to update it because it may not exist, but we also don't want to overwrite.

      List<InchiDescriptor> substrates = new ArrayList<>();
      if (parentId >= 0) {
        try {
          Chemical parent = connection.getChemicalFromChemicalUUID(parentId);
          upsert(constructReachable(parent.getInChI()));
          InchiDescriptor parentDescriptor = new InchiDescriptor(constructReachable(parent.getInChI()));
          substrates.add(parentDescriptor);
        } catch (NullPointerException e){
          LOGGER.info("Null pointer, unable to write parent.");
        }
      }

      // Get the actual chemical that is the product of the above chemical.
      Chemical current = connection.getChemicalFromChemicalUUID(currentId);

      // Update source as reachables, as these files are parsed from `cascade` construction
      if (!substrates.isEmpty()) {
        Precursor pre = new Precursor(substrates, "reachables");
        updateWithPrecursor(current.getInChI(), pre);
      } else {
        // TODO add a special native class?
        upsert(constructReachable(current.getInChI()));
      }
    } catch (IOException e) {
      // We can only work with files we can parse, so if we can't
      // parse the file we just don't do anything and submit an error.
      LOGGER.warn("Unable to load file " + file.getAbsolutePath());
    } catch (JSONException e){
      LOGGER.warn("Unable to parse JSON of file at " + file.getAbsolutePath());
    }
  }

  public void updateFromReachableFiles(List<File> files){
    files.stream().forEach(this::updateFromReachablesFile);
  }

  public void updateFromReachableDir(File file){
    List<File> validFiles = Arrays.stream(file.listFiles()).filter(x ->
            x.getName().startsWith(("c")) && x.getName().endsWith("json")).collect(Collectors.toList());
    LOGGER.info("Found %d reachables files.",validFiles.size());
    updateFromReachableFiles(validFiles);
  }

  public static void main(String[] args) throws IOException {
  //    Loader loader = new Loader();
  //    loader.loadReachables(new File("/Volumes/shared-data/Thomas/L2inchis.test20"));
  //    loader.updateWithPrecursorData("InChI=1S/C2H5NO2/c3-1-2(4)5/h1,3H2,(H,4,5)", new PrecursorData());
    Loader loader = new Loader();
    // Load all cascades
    loader.updateFromReachableDir(new File("/Volumes/shared-data/Michael/WikipediaProject/Reachables/r-2016-11-16-data"));
  }
}
