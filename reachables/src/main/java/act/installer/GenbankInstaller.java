/*************************************************************************
*                                                                        *
*  This file is part of the 20n/act project.                             *
*  20n/act enables DNA prediction for synthetic biology/bioengineering.  *
*  Copyright (C) 2017 20n Labs, Inc.                                     *
*                                                                        *
*  Please direct all queries to act@20n.com.                             *
*                                                                        *
*  This program is free software: you can redistribute it and/or modify  *
*  it under the terms of the GNU General Public License as published by  *
*  the Free Software Foundation, either version 3 of the License, or     *
*  (at your option) any later version.                                   *
*                                                                        *
*  This program is distributed in the hope that it will be useful,       *
*  but WITHOUT ANY WARRANTY; without even the implied warranty of        *
*  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the         *
*  GNU General Public License for more details.                          *
*                                                                        *
*  You should have received a copy of the GNU General Public License     *
*  along with this program.  If not, see <http://www.gnu.org/licenses/>. *
*                                                                        *
*************************************************************************/

package act.installer;

import act.installer.sequence.GenbankSeqEntry;
import act.installer.sequence.GenbankSeqEntryFactory;
import act.server.DBIterator;
import act.server.MongoDB;
import act.shared.Organism;
import act.shared.Seq;
import com.act.biointerpretation.Utils.OrgMinimalPrefixGenerator;
import com.act.utils.parser.GenbankInterpreter;
import com.mongodb.DBObject;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.biojava.nbio.core.sequence.features.FeatureInterface;
import org.biojava.nbio.core.sequence.template.AbstractSequence;
import org.biojava.nbio.core.sequence.template.Compound;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class GenbankInstaller {
  private static final Logger LOGGER = LogManager.getFormatterLogger(GenbankInstaller.class);
  private static final GenbankSeqEntryFactory seqEntryFactory = new GenbankSeqEntryFactory();
  private static final String OPTION_GENBANK_PATH = "p";
  private static final String OPTION_DB_NAME = "d";
  private static final String OPTION_SEQ_TYPE = "s";
  private static final String ACCESSION = "accession";
  private static final String NAME = "name";
  private static final String COUNTRY_CODE = "country_code";
  private static final String PATENT_NUMBER = "patent_number";
  private static final String PATENT_YEAR = "patent_year";
  private static final String SYNONYMS = "synonyms";
  private static final String PRODUCT_NAMES = "product_names";
  private static final String DNA = "DNA";
  private static final String CDS = "CDS";
  private static final String PROTEIN_ID = "protein_id";
  private static final String PROTEIN = "Protein";
  private static final String VAL = "val";
  private static final String SRC = "src";
  private static final String PMID = "PMID";
  private static final String PATENT = "Patent";

  //  http://www.ncbi.nlm.nih.gov/Sequin/acc.html
  public static final Pattern PROTEIN_ACCESSION_PATTERN = Pattern.compile("[a-zA-Z]{3}\\d{5}");
  // matches WGS and MGA sequence accession patterns since they appear in Nucleotide files as well
  public static final Pattern NUCLEOTIDE_ACCESSION_PATTERN =
      Pattern.compile("[a-zA-Z]\\d{5}|[a-zA-Z]{2}\\d{6}|[a-zA-Z]{4}\\d{8,10}|[a-zA-Z]{5}\\d{7}");

  public static final String HELP_MESSAGE = StringUtils.join(new String[]{
      "This class is the driver to write sequence data from a Genbank file to our database. It can be used on the ",
      "command line with a file path as a parameter."}, "");

  public static final List<Option.Builder> OPTION_BUILDERS = new ArrayList<Option.Builder>() {{
    add(Option.builder(OPTION_GENBANK_PATH)
        .argName("genbank file")
        .desc("genbank file containing sequence and annotations")
        .hasArg()
        .longOpt("genbank")
        .required()
    );
    add(Option.builder(OPTION_DB_NAME)
        .argName("db name")
        .desc("name of the database to be queried")
        .hasArg()
        .longOpt("database")
        .required()
    );
    add(Option.builder(OPTION_SEQ_TYPE)
        .argName("sequence type")
        .desc("declares whether the sequence type is DNA or Protein")
        .hasArg()
        .longOpt("sequence")
        .required()
    );
    add(Option.builder("h")
        .argName("help")
        .desc("Example of usage: -p filepath.gb -d marvin -s DNA")
        .longOpt("help")
    );
  }};

  public static final HelpFormatter HELP_FORMATTER = new HelpFormatter();

  static {
    HELP_FORMATTER.setWidth(100);
  }

  File genbankFile;
  String seqType;
  MongoDB db;
  Map<String, String> minimalPrefixMapping;

  // the minimalPrefixMapping is generated by OrgMinimalPrefixGenerator
  public GenbankInstaller (File genbankFile, String seqType, MongoDB db, Map<String, String> minimalPrefixMapping) {
    this.genbankFile = genbankFile;
    this.seqType = seqType;
    this.db = db;
    this.minimalPrefixMapping = minimalPrefixMapping;
  }

  public void init() throws Exception {
    GenbankInterpreter reader = new GenbankInterpreter(genbankFile, seqType);
    reader.init();
    List<AbstractSequence> sequences = reader.getSequences();

    int sequenceCount = 0;

    GenbankSeqEntry seqEntry;

    for (AbstractSequence sequence : sequences) {
      if (seqType.equals(DNA)) {
        for (FeatureInterface<AbstractSequence<Compound>, Compound> feature :
            (List<FeatureInterface<AbstractSequence<Compound>, Compound>>) sequence.getFeatures()) {
          if (feature.getType().equals(CDS) && feature.getQualifiers().containsKey(PROTEIN_ID)) {
            seqEntry = seqEntryFactory.createFromDNASequenceReference(sequence, feature.getQualifiers(), db,
                minimalPrefixMapping);
            addSeqEntryToDb(seqEntry, db);
            sequenceCount++;
          }
        }

      } else if (seqType.equals(PROTEIN)) {
        seqEntry = seqEntryFactory.createFromProteinSequenceReference(sequence, db, minimalPrefixMapping);
        addSeqEntryToDb(seqEntry, db);
        sequenceCount++;
      }
    }

    LOGGER.info("%s sequences installed in the db", sequenceCount);
  }

  /**
   * Verifies the accession string according to the standard Genbank/Uniprot accession qualifications
   * @param proteinAccession the accession string to be validated
   * @param accessionPattern the pattern that the accession string should match
   * @return
   */
  public static boolean verifyAccession(String proteinAccession, Pattern accessionPattern) {
    return accessionPattern.matcher(proteinAccession).matches();
  }

  /**
   * Checks if the new value already exists in the field. If so, doesn't update the metadata. If it doesn't exist,
   * appends the new value to the data.
   * @param field the key referring to the array in the metadata we wish to update
   * @param value the value we wish to add to the array
   * @param data the metadata
   * @return the updated metadata JSONObject
   */
  public static JSONObject updateArrayField(String field, String value, JSONObject data) {
    if (value == null || value.isEmpty()) {
      return data;
    }

    if (data.has(field)) {
      JSONArray fieldData = data.getJSONArray(field);

      for (int i = 0; i < fieldData.length(); i++) {
        if (fieldData.get(i).toString().equals(value)) {
          return data;
        }
      }
    }

    return data.append(field, value);
  }

  /**
   * Updates the accession JSONObject for the given accessions type
   * @param newAccessionObject the new accession object to load in the new accessions of the given type
   * @param metadata contains the accession object to be updated
   * @param accType the type of accessions to update
   * @param accessionPattern the accession pattern to validate the accession string according to Genbank/Uniprot
   *                         standards
   * @return the metadata containing the updated accession mapping
   */
  public static JSONObject updateAccessions(JSONObject newAccessionObject, JSONObject metadata, Seq.AccType accType,
                                      Pattern accessionPattern) {
    JSONObject oldAccessionObject = metadata.getJSONObject(ACCESSION);

    if (newAccessionObject.has(accType.toString())) {
      JSONArray newAccTypeAccessions = newAccessionObject.getJSONArray(accType.toString());

      for (int i = 0; i < newAccTypeAccessions.length(); i++) {
        if (!verifyAccession(newAccTypeAccessions.getString(i), accessionPattern)) {
          LOGGER.error("%s accession not the right format: %s\n", accType.toString(),
              newAccTypeAccessions.getString(i));
          continue;
        }

        oldAccessionObject = updateArrayField(accType.toString(), newAccTypeAccessions.getString(i),
            oldAccessionObject);
      }

    }

    return metadata.put(ACCESSION, oldAccessionObject);
  }

  /**
   * Updates metadata and reference fields with the information extracted from file
   * @param se an instance of the GenbankSeqEntry class that extracts all the relevant information from a sequence
   *           object
   * @param db reference to the database that should be queried and updated
   */
  private void addSeqEntryToDb(GenbankSeqEntry se, MongoDB db) {
    List<Seq> seqs = se.getMatchingSeqs();

    // no prior data on this sequence
    if (seqs.isEmpty()) {
      se.writeToDB(db, Seq.AccDB.genbank);
      return;
    }

    // update prior data
    for (Seq seq : seqs) {
      JSONObject metadata = seq.getMetadata();

      JSONObject accessions = se.getAccession();

      if (!metadata.has(ACCESSION)) {
        metadata.put(ACCESSION, accessions);
      } else {
        metadata = updateAccessions(accessions, metadata, Seq.AccType.genbank_nucleotide,
            NUCLEOTIDE_ACCESSION_PATTERN);
        metadata = updateAccessions(accessions, metadata, Seq.AccType.genbank_protein, PROTEIN_ACCESSION_PATTERN);
      }

      List<String> geneSynonyms = se.getGeneSynonyms();

      if (se.getGeneName() != null) {
        if (!metadata.has(NAME) || metadata.get(NAME) == null) {
          metadata.put(NAME, se.getGeneName());
        } else if (!se.getGeneName().equals(metadata.get(NAME))) {
          geneSynonyms.add(se.getGeneName());
        }
      }

      for (String geneSynonym : geneSynonyms) {
        if (!geneSynonym.equals(metadata.get(NAME))) {
          metadata = updateArrayField(SYNONYMS, geneSynonym, metadata);
        }
      }

      if (se.getProductName() != null) {
        metadata = updateArrayField(PRODUCT_NAMES, se.getProductName().get(0), metadata);
      }

      seq.setMetadata(metadata);

      db.updateMetadata(seq);

      List<JSONObject> oldRefs = seq.getReferences();
      List<JSONObject> newPmidRefs = se.getPmids();
      List<JSONObject> newPatentRefs = se.getPatents();

      if (!oldRefs.isEmpty()) {
        Set<String> oldPmids = new HashSet<>();

        for (JSONObject oldRef : oldRefs) {
          if (oldRef.get(SRC).equals(PMID)) {
            oldPmids.add(oldRef.getString(VAL));
          }
        }

        for (JSONObject newPmidRef : newPmidRefs) {
          if (!oldPmids.contains(newPmidRef.getString(VAL))) {
            oldRefs.add(newPmidRef);
          }
        }

        for (JSONObject newPatentRef : newPatentRefs) {
          Boolean patentExists = false;
          String countryCode = (String) newPatentRef.get(COUNTRY_CODE);
          String patentNumber = (String) newPatentRef.get(PATENT_NUMBER);
          String patentYear = (String) newPatentRef.get(PATENT_YEAR);

          // checks if any patents are equivalent
          for (JSONObject newRef : oldRefs) {
            if (newRef.get(SRC).equals(PATENT) && newRef.get(COUNTRY_CODE).equals(countryCode)
                && newRef.get(PATENT_NUMBER).equals(patentNumber) && newRef.get(PATENT_YEAR).equals(patentYear)) {
              patentExists = true;
            }
          }

          if (!patentExists) {
            oldRefs.add(newPatentRef);
          }
        }

        seq.setReferences(oldRefs);

      } else {
        seq.setReferences(se.getRefs());
      }

      if (seq.getReferences() != null) {
        db.updateReferences(seq);
      }
    }
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
      LOGGER.error("Argument parsing failed: %s", e.getMessage());
      HELP_FORMATTER.printHelp(GenbankInstaller.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    if (cl.hasOption("help")) {
      HELP_FORMATTER.printHelp(GenbankInstaller.class.getCanonicalName(), HELP_MESSAGE, opts, null, true);
      System.exit(1);
    }

    File genbankFile = new File(cl.getOptionValue(OPTION_GENBANK_PATH));
    String dbName = cl.getOptionValue(OPTION_DB_NAME);
    String seqType = cl.getOptionValue(OPTION_SEQ_TYPE);

    if (!genbankFile.exists()) {
      String msg = String.format("Genbank file path is null");
      LOGGER.error(msg);
      throw new RuntimeException(msg);
    } else {
      MongoDB db = new MongoDB("localhost", 27017, dbName);

      DBIterator iter = db.getDbIteratorOverOrgs();

      Iterator<Organism> orgIterator = new Iterator<Organism> () {
        @Override
        public boolean hasNext() {
          boolean hasNext = iter.hasNext();
          if (!hasNext)
            iter.close();
          return hasNext;
        }

        @Override
        public Organism next() {
          DBObject o = iter.next();
          return db.convertDBObjectToOrg(o);
        }

      };

      OrgMinimalPrefixGenerator prefixGenerator = new OrgMinimalPrefixGenerator(orgIterator);
      Map<String, String> minimalPrefixMapping = prefixGenerator.getMinimalPrefixMapping();

      GenbankInstaller installer = new GenbankInstaller(genbankFile, seqType, db, minimalPrefixMapping);
      installer.init();
    }

  }
}
