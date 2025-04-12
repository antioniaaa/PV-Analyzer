package de.anton.pv.analyser.pv_analyzer.algorithms;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;

/**
 * Utility class for calculating Euclidean distances.
 */
public final class DistanceUtils {

    private DistanceUtils() { throw new IllegalStateException("Utility class"); }

    /** Distance for Clustering (DC Power vs DC Voltage). */
    public static double euclideanDistanceCluster(CalculatedDataPoint p1, CalculatedDataPoint p2) {
        if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY;
        double p1Power = p1.getDcLeistungKW(); double p1Voltage = p1.getDcSpannungV();
        double p2Power = p2.getDcLeistungKW(); double p2Voltage = p2.getDcSpannungV();
        if (Double.isNaN(p1Power) || Double.isNaN(p1Voltage) || Double.isNaN(p2Power) || Double.isNaN(p2Voltage)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = p1Power - p2Power; double dy = p1Voltage - p2Voltage;
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** Distance for Outlier Detection (Specific Power vs DC Voltage). */
    public static double euclideanDistanceOutlier(CalculatedDataPoint p1, CalculatedDataPoint p2) {
        if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY;
        double p1SpecPower = p1.getSpezifischeLeistung(); double p1Voltage = p1.getDcSpannungV();
        double p2SpecPower = p2.getSpezifischeLeistung(); double p2Voltage = p2.getDcSpannungV();
        if (Double.isNaN(p1SpecPower) || Double.isNaN(p1Voltage) || Double.isNaN(p2SpecPower) || Double.isNaN(p2Voltage)) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = p1SpecPower - p2SpecPower; double dy = p1Voltage - p2Voltage;
        return Math.sqrt(dx * dx + dy * dy);
    }
}