package de.anton.pv.analyser.pv_analyzer.model;

import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Represents a data point for a specific tracker, combining raw measurements
 * with calculated metrics and analysis results (clustering, outliers, performance).
 * Can represent data from a single timestamp or the point with max vector length
 * within an interval (storing the original timestamp).
 */
public class CalculatedDataPoint {

    private static final Logger logger = LoggerFactory.getLogger(CalculatedDataPoint.class);

    // Input Data
    private final String name;
    private final double dcLeistungKW;
    private final double dcSpannungV;
    private final TrackerInfo trackerInfoRef;
    private final ModuleInfo moduleInfoRef;
    private final String sourceTimestamp;

    // Basic Calculated Metrics
    private final double nennleistungKWp;
    private final int anzahlStrings;
    private final String ausrichtung;
    private final double spezifischeLeistung;
    private final double stromJeStringA;
    private final double ohm;

    // Optional Calculated Metrics
    private final boolean moduleDataAvailable;
    private final double anzahlModuleJeString;
    private final double diffSpannungModulVmpp;
    private final double diffStromModulImpp;
    private final double diffLeistungModulPmpp;

    // Analysis Results (Mutable)
    private int clusterGroup = MyOPTICS.NOISE;
    private boolean isOutlier = false;
    private String performanceLabel = ""; // "hoch", "niedrig", "median", oder ""

    /**
     * Constructor for CalculatedDataPoint.
     */
    public CalculatedDataPoint(String name, double dcLeistungKW, double dcSpannungV,
                               TrackerInfo trackerInfo, ModuleInfo moduleInfo, String sourceTimestamp) {

        this.name = Objects.requireNonNull(name, "Data point name cannot be null");
        this.dcLeistungKW = dcLeistungKW;
        this.dcSpannungV = dcSpannungV;
        this.trackerInfoRef = Objects.requireNonNull(trackerInfo, "TrackerInfo cannot be null for " + name);
        this.moduleInfoRef = moduleInfo;
        this.sourceTimestamp = Objects.requireNonNull(sourceTimestamp, "Source timestamp cannot be null for " + name);

        // Extract from TrackerInfo
        this.nennleistungKWp = trackerInfo.getNennleistungkWp();
        this.anzahlStrings = trackerInfo.getAnzahlStrings();
        this.ausrichtung = trackerInfo.getAusrichtung() != null ? trackerInfo.getAusrichtung() : "Unbekannt";

        // Basic Calculations
        this.spezifischeLeistung = (!Double.isNaN(dcLeistungKW) && Math.abs(this.nennleistungKWp) > 1e-9)
                                   ? dcLeistungKW / this.nennleistungKWp : Double.NaN;
        this.stromJeStringA = (!Double.isNaN(dcLeistungKW) && Math.abs(dcSpannungV) > 1e-9 && anzahlStrings > 0)
                              ? (dcLeistungKW * 1000.0) / dcSpannungV / anzahlStrings : Double.NaN;
        this.ohm = (!Double.isNaN(dcSpannungV) && !Double.isNaN(this.stromJeStringA) && Math.abs(this.stromJeStringA) > 1e-9)
                   ? dcSpannungV / this.stromJeStringA : Double.NaN;

        // Optional Calculations
        boolean moduleInfoPresent = this.moduleInfoRef != null;
        double tempAnzahlModuleJeString = Double.NaN; double tempDiffSpannungModulVmpp = Double.NaN; double tempDiffStromModulImpp = Double.NaN; double tempDiffLeistungModulPmpp = Double.NaN; boolean tempModuleDataCalculated = false;
        if (moduleInfoPresent) { try { boolean canCalcModules = !Double.isNaN(this.nennleistungKWp) && moduleInfoRef.getPnennKWp() > 1e-9 && anzahlStrings > 0; tempAnzahlModuleJeString = canCalcModules ? this.nennleistungKWp / moduleInfoRef.getPnennKWp() / anzahlStrings : Double.NaN; boolean canCalcVoltage = !Double.isNaN(dcSpannungV) && !Double.isNaN(tempAnzahlModuleJeString) && Math.abs(tempAnzahlModuleJeString) > 1e-9; double spannungModulV = canCalcVoltage ? dcSpannungV / tempAnzahlModuleJeString : Double.NaN; tempDiffSpannungModulVmpp = (!Double.isNaN(spannungModulV) && !Double.isNaN(moduleInfoRef.getVmppV())) ? spannungModulV - moduleInfoRef.getVmppV() : Double.NaN; double stromstaerkeModulA = this.stromJeStringA; tempDiffStromModulImpp = (!Double.isNaN(stromstaerkeModulA) && !Double.isNaN(moduleInfoRef.getImppA())) ? stromstaerkeModulA - moduleInfoRef.getImppA() : Double.NaN; boolean canCalcPower = !Double.isNaN(stromstaerkeModulA) && !Double.isNaN(spannungModulV); double leistungModulKW = canCalcPower ? (stromstaerkeModulA * spannungModulV) / 1000.0 : Double.NaN; tempDiffLeistungModulPmpp = (!Double.isNaN(leistungModulKW) && !Double.isNaN(moduleInfoRef.getPmppKW())) ? leistungModulKW - moduleInfoRef.getPmppKW() : Double.NaN; tempModuleDataCalculated = true; } catch (ArithmeticException ae) { logger.warn("Arithmetic error during optional calculation for {}: {}", name, ae.getMessage()); tempAnzahlModuleJeString = Double.NaN; tempDiffSpannungModulVmpp = Double.NaN; tempDiffStromModulImpp = Double.NaN; tempDiffLeistungModulPmpp = Double.NaN; tempModuleDataCalculated = false; } catch (Exception e) { logger.error("Unexpected error during optional calculation for {}: {}", name, e.getMessage(), e); tempAnzahlModuleJeString = Double.NaN; tempDiffSpannungModulVmpp = Double.NaN; tempDiffStromModulImpp = Double.NaN; tempDiffLeistungModulPmpp = Double.NaN; tempModuleDataCalculated = false; } }
        this.anzahlModuleJeString = tempAnzahlModuleJeString; this.diffSpannungModulVmpp = tempDiffSpannungModulVmpp; this.diffStromModulImpp = tempDiffStromModulImpp; this.diffLeistungModulPmpp = tempDiffLeistungModulPmpp; this.moduleDataAvailable = moduleInfoPresent && tempModuleDataCalculated;
    }

    // Getters
    public String getName() { return name; }
    public double getDcLeistungKW() { return dcLeistungKW; }
    public double getSpezifischeLeistung() { return spezifischeLeistung; }
    public double getDcSpannungV() { return dcSpannungV; }
    public double getNennleistungKWp() { return nennleistungKWp; }
    public double getStromJeStringA() { return stromJeStringA; }
    public double getOhm() { return ohm; }
    public int getAnzahlStrings() { return anzahlStrings; }
    public String getAusrichtung() { return ausrichtung; }
    public String getSourceTimestamp() { return sourceTimestamp; }
    public boolean isModuleDataAvailable() { return moduleDataAvailable; }
    public double getAnzahlModuleJeString() { return anzahlModuleJeString; }
    public double getDiffSpannungModulVmpp() { return diffSpannungModulVmpp; }
    public double getDiffStromModulImpp() { return diffStromModulImpp; }
    public double getDiffLeistungModulPmpp() { return diffLeistungModulPmpp; }
    public int getClusterGroup() { return clusterGroup; }
    public boolean isOutlier() { return isOutlier; }
    public String getPerformanceLabel() { return performanceLabel; }
    public TrackerInfo getTrackerInfo() { return trackerInfoRef; }
    public ModuleInfo getModuleInfo() { return moduleInfoRef; }

    // Setters
    public void setClusterGroup(int clusterGroup) { this.clusterGroup = clusterGroup; }
    public void setOutlier(boolean outlier) { isOutlier = outlier; }
    public void setPerformanceLabel(String performanceLabel) { this.performanceLabel = (performanceLabel == null) ? "" : performanceLabel; }

    // Standard Methods
    @Override public String toString() { return "CalculatedDataPoint{" + "name='" + name + '\'' + ", sourceTs='" + sourceTimestamp + '\'' + ", dcLeistungKW=" + dcLeistungKW + ", dcSpannungV=" + dcSpannungV + ", spezLeistung=" + spezifischeLeistung + ", cluster=" + clusterGroup + ", outlier=" + isOutlier + ", perf='" + performanceLabel + "'}"; }
    @Override public boolean equals(Object o) { if (this == o) return true; if (o == null || getClass() != o.getClass()) return false; CalculatedDataPoint that = (CalculatedDataPoint) o; return Objects.equals(name, that.name) && Objects.equals(sourceTimestamp, that.sourceTimestamp); }
    @Override public int hashCode() { return Objects.hash(name, sourceTimestamp); }
}