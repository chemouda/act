package com.act.lcms;

import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Collections;
import java.util.Comparator;
import java.io.IOException;
import java.io.PrintStream;
import java.io.FileOutputStream;
import org.apache.commons.lang3.tuple.Pair;

public class MS2Simple {

  class YZ {
    Double mz;
    Double intensity;

    public YZ(Double mz, Double intensity) {
      this.mz = mz;
      this.intensity = intensity;
    }
  }

  class MS2Collected {
    Double triggerTime;
    Double voltage;
    List<YZ> ms2;

    public MS2Collected(Double trigTime, Double collisionEv, List<YZ> ms2) {
      this.triggerTime = trigTime;
      this.ms2 = ms2;
      this.voltage = collisionEv;
    }
  }

  // Rounding upto 2 decimal places should result in pretty 
  // much an identical match on mz in the MS2 spectra
  final static Double MS2_MZ_COMPARE_TOLERANCE = 0.005; 

  // In the MS1 case, we look for a very tight window 
  // because we do not noise to broaden our signal
  final static Double MS1_MZ_TOLERANCE = 0.001;

  // when aggregating the MS1 signal, we do not expect
  // more than these number of measurements within the
  // mz window specified by the tolerance above
  static final Integer MAX_MZ_IN_WINDOW = 3;

  // Use the time window to only identify identical scans
  // hence the infinitely small window
  final static Double TIME_TOLERANCE = 0.1 / 1e3d;

  // the number of peaks to compare across the MS2s of 
  // the standard and the strain
  final static Integer REPORT_TOP_N = 40;

  // the threshold we compare against after computing the
  // weighted matching of peaks (sum of [0,1] intensities)
  // Rationale for keeping it at 1.0: If the MS2 spectra
  // consists, in the degenerate case of 1 peak, and that
  // matches across the two spectra, we should declare success
  final static Double THRESHOLD_WEIGHTED_PEAK = 1.0;

  // log?
  final static boolean LOG_PEAKS_TO_STDOUT = false;

  private List<LCMS2MZSelection> filterByTriggerMass(Iterator<LCMS2MZSelection> ms2Scans, Double targetMass) {
    // Look for precisely this time point, so infinitely small window
    Double mzLow = targetMass - MS1_MZ_TOLERANCE;
    Double mzHigh = targetMass + MS1_MZ_TOLERANCE;

    List<LCMS2MZSelection> relevantMS2Scans = new ArrayList<>();
    while (ms2Scans.hasNext()) {
      LCMS2MZSelection scan  = ms2Scans.next();
      Double targetWindow = scan.getIsolationWindowTargetMZ();
      if (targetWindow >= mzLow && targetWindow <= mzHigh) {
        relevantMS2Scans.add(scan);
      }
    }

    if (relevantMS2Scans.size() < 1) {
      String errmsg = String.format("SEVERE ERR: found no matching MS2 scans for MS1 target mass %f", targetMass);
      throw new RuntimeException(errmsg);
    }

    return relevantMS2Scans;
  }

  List<YZ> spectrumToYZList(LCMSSpectrum spectrum) {
    List<YZ> yzList = new ArrayList<>(spectrum.getIntensities().size());
    for (Pair<Double, Double> p : spectrum.getIntensities()) {
      yzList.add(new YZ(p.getLeft(), p.getRight()));
    }
    return yzList;
  }

  List<MS2Collected> getSpectraForMatchingScans(
      List<LCMS2MZSelection> relevantMS2Selections, Iterator<LCMSSpectrum> ms2Spectra) {
    List<MS2Collected> ms2s = new ArrayList<>();

    Iterator<LCMS2MZSelection> selectionIterator = relevantMS2Selections.iterator();
    if (!selectionIterator.hasNext()) {
      // Previous checks should have prevented this.
      throw new RuntimeException("No scans available for spectrum matching");
    }
    LCMS2MZSelection thisSelection = selectionIterator.next();
    // TODO: handle other time units more gracefully.
    if (!"minute".equals(thisSelection.getTimeUnit())) {
      throw new RuntimeException(String.format(
          "Expected 'minute' for MS2 scan selection time unit, but found '%s'", thisSelection.getTimeUnit()));
    }
    Double ms2Time = thisSelection.getTimeVal() * 60.0d; // mzML times tend to be in minutes;
    Double collisionEnergy = thisSelection.getCollisionEnergy(); // assumed in electronvols
    Double tLow = ms2Time - TIME_TOLERANCE;
    Double tHigh = ms2Time + TIME_TOLERANCE;

    while (ms2Spectra.hasNext()) {
      boolean advanceMS2Selection = false;

      LCMSSpectrum spectrum = ms2Spectra.next();
      Double sTime = spectrum.getTimeVal();
      if (sTime >= tLow && sTime <= tHigh) {
        // We found a matching scan!
        MS2Collected ms2 = new MS2Collected(ms2Time, collisionEnergy, this.spectrumToYZList(spectrum));
        ms2s.add(ms2);
        advanceMS2Selection = true;
      } else if (sTime > ms2Time) {
        System.err.format("ERROR: found spectrum at time %f when searching for MS2 scan at %f, skipping MS2 scan\n",
          sTime, ms2Time);
        advanceMS2Selection = true;
      } // Otherwise, this spectrum's time doesn't match the time point of the next relevant MS2 scan.  Skip it!

      if (advanceMS2Selection) {
        if (!selectionIterator.hasNext()) {
          // No more relevant scans to search for.
          break;
        }
        thisSelection = selectionIterator.next();
        ms2Time = thisSelection.getTimeVal() * 60.0d; // Assume time units are consistent across all mzML entries.
        tLow = ms2Time - TIME_TOLERANCE;
        tHigh = ms2Time + TIME_TOLERANCE;

      }
    }

    if (selectionIterator.hasNext()) {
      System.err.format("ERROR: ran out of spectra to match against MS2 scans with some scans still unmatched.\n");
    }

    return ms2s;
  }

  private static boolean areNCFiles(String[] fnames) {
    for (String n : fnames) {
      System.out.println(".nc file = " + n);
      if (!n.endsWith(".nc"))
        return false;
    }
    return true;
  }

  private List<MS2Collected> findPeaksTriggeredByMZ(Double mz, String ms2mzML, String ms2nc)
    throws Exception {

    // the first .nc is the ion trigger on the mz extracted
    List<LCMS2MZSelection> matchingScans =
        filterByTriggerMass(new LCMS2mzMLParser().getIterator(ms2mzML), mz);

    Iterator<LCMSSpectrum> spectrumIterator = new LCMSNetCDFParser().getIterator(ms2nc);
    return getSpectraForMatchingScans(matchingScans, spectrumIterator);
  }

  private YZ getMatchingPeak(YZ toLook, List<YZ> matchAgainst) {
    Double mz = toLook.mz;
    YZ match = null;
    YZ minDistMatch = null;
    for (YZ peak : matchAgainst) {
      Double dist = Math.abs(peak.mz - mz);
      // we look for a peak that is within MS2_MZ_COMPARE_TOLERANCE of mz
      if (dist < MS2_MZ_COMPARE_TOLERANCE) {

        // this is a match, make sure it is the only match
        if (match != null) {
          System.out.format("SEVERE: MS2_MZ_COMPARE_TOLERANCE might be too wide. MS2 peak %.4f has >1 matches.\n" + 
              "\tMatch 1: %.4f\t Match 2: %.4f\n", mz, match.mz, peak.mz);
        }

        match = peak;
      }

      // bookkeeping for how close it got, in case no matches within precision
      if (minDistMatch == null || Math.abs(minDistMatch.mz - mz) > dist) {
        minDistMatch = peak;
      }
    }
    if (match != null) {
      System.out.format("Peak %8.4f (%6.2f%%) - MATCHES -    PEAK: %8.4f (%6.2f%%) at DISTANCE: %.5f\n", mz, toLook.intensity, match.mz, match.intensity, Math.abs(match.mz - mz));
    } else {
      System.out.format("Peak %8.4f (%6.2f%%) - NO MTCH - CLOSEST: %8.4f (%6.2f%%) at DISTANCE: %.5f\n", mz, toLook.intensity, minDistMatch.mz, minDistMatch.intensity, Math.abs(minDistMatch.mz - mz));
    }
    return match;
  }

  private Double weightedMatch(MS2Collected A, MS2Collected B) {
    Double weightedSum = 0.0;

    // we should go through the peaks in descending order of intensity
    // so that get reported to the output in that order
    List<YZ> orderedBms2 = new ArrayList<>(B.ms2);
    Collections.sort(orderedBms2, new Comparator<YZ>() {
      public int compare(YZ a, YZ b) {
        return b.intensity.compareTo(a.intensity);
      }
    });

    // once a peak is matched, we should remove it from the available
    // set to be matched further
    List<YZ> toMatch = new ArrayList<>(A.ms2);

    for (YZ peak : orderedBms2) {
      YZ matchInA = getMatchingPeak(peak, toMatch);
      if (matchInA != null) {
        // this YZ peak in B has a match `matchInA` in A's MS2 peaks
        // if the aggregate peak across both spectra is high, we give it a
        // high score; by weighing it with the intensity percentage
        Double intensityPc = (peak.intensity + matchInA.intensity) / 2.0;
        // scale it back to [0,1] from [0,100]%
        Double intensity = intensityPc / 100; 

        weightedSum += intensity;
        toMatch.remove(matchInA);
      }
    }

    return weightedSum;

  }

  private boolean doMatch(MS2Collected A, MS2Collected B) {
    Double weightedPeakMatch = weightedMatch(A, B);

    System.out.format("Weighted peak match: %.2f >= %.2f\n", weightedPeakMatch, THRESHOLD_WEIGHTED_PEAK);
    boolean isMatch = weightedPeakMatch >= THRESHOLD_WEIGHTED_PEAK;

    return isMatch;
  }

  private void findAndPlotMatchingMS2Scans(Double mz,
                                           String ms2mzML, String ms2nc,
                                           String outPrefix, String fmt) throws IOException {
    List<MS2Collected> ms2Peaks = null;

    try {
      ms2Peaks = findPeaksTriggeredByMZ(mz, ms2mzML, ms2nc);
      System.out.println("Standard: MS2 fragmentation trigged on " + mz);
    } catch (Exception e) {
      System.out.println("Standard: " + e.getMessage());
    }

    plot(ms2Peaks, mz, outPrefix, fmt);
  }

  private void plot(List<MS2Collected> ms2Spectra, Double mz, String outPrefix, String fmt)
    throws IOException {

    String outPDF = outPrefix + "." + fmt;
    String outDATA = outPrefix + ".data";

    // Write data output to outfile
    PrintStream out = new PrintStream(new FileOutputStream(outDATA));

    List<String> plotID = new ArrayList<>(ms2Spectra.size());
    for (MS2Collected yzSlice : ms2Spectra) {
      plotID.add(String.format("time: %.4f, volts: %.4f", yzSlice.triggerTime, yzSlice.voltage));
      // print out the spectra to outDATA
      for (YZ yz : yzSlice.ms2) {
        out.format("%.4f\t%.4f\n", yz.mz, yz.intensity);
        out.flush();
      }
      // delimit this dataset from the rest
      out.print("\n\n");
    }

    // close the .data
    out.close();

    // render outDATA to outPDF using gnuplot
    // 105.0 here means 105% for the y-range of a [0%:100%] plot. We want to leave some buffer space at
    // at the top, and hence we go a little outside of the 100% max range.
    new Gnuplotter().plot2DImpulsesWithLabels(outDATA, outPDF, plotID.toArray(new String[plotID.size()]), 
        mz + 50.0, "mz", 105.0, "intensity (%)", fmt);
  }

  public static void main(String[] args) throws Exception {
    if (args.length < 4
        || !areNCFiles(new String[] {args[3]})
        ) {
      throw new RuntimeException("Needs: \n" + 
          "(1) mz for main product, e.g., 431.1341983 (ononin) \n" +
          "(2) prefix for .data and rendered .pdf \n" +
          "(3) STD: mzML file from MS2 run (to extract trigger masses) \n" +
          "(4) STD: NetCDF .nc file 02.nc from MSMS run"
          );
    }

    String fmt = "pdf";
    Double mz = Double.parseDouble(args[0]);
    String outPrefix = args[1];
    String ms2mzml = args[2];
    String ms2nc = args[3];

    MS2Simple c = new MS2Simple();
    c.findAndPlotMatchingMS2Scans(mz, ms2mzml, ms2nc, outPrefix, fmt);
  }
}
