package de.anton.pv.analyser.pv_analyzer.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Utility class for parameter estimation heuristics, like finding the knee point
 * in a sorted list of distances (e.g., k-distance graph).
 */
public final class ParameterEstimationUtils {

    private static final Logger logger = LoggerFactory.getLogger(ParameterEstimationUtils.class);

    private ParameterEstimationUtils() { throw new IllegalStateException("Utility class"); }

    /**
     * Finds the value at the "knee" or "elbow" point of a sorted list of values.
     * Uses the Kneedle approach: finds the point with the maximum perpendicular distance
     * to the line connecting the first and last points after normalization.
     */
    public static double findKneePointValue(List<Double> sortedValues) {
        if (sortedValues == null || sortedValues.size() < 3) {
            logger.warn("Cannot find knee point: List is null or too small (< 3 elements).");
            return -1.0;
        }

        int n = sortedValues.size();
        // Ensure values are not null before accessing
        Double firstValObj = sortedValues.get(0);
        Double lastValObj = sortedValues.get(n - 1);
        if(firstValObj == null || lastValObj == null) {
            logger.warn("Cannot find knee point: List contains null values at start or end.");
            return -1.0;
        }
        double firstVal = firstValObj;
        double lastVal = lastValObj;


        if (Math.abs(lastVal - firstVal) < 1e-9) {
            logger.warn("Cannot find knee point: All values in the list are (almost) constant.");
            return -1.0;
        }

        // Normalize X coordinates (indices) to [0, 1]
        double[] xNorm = new double[n];
        for (int i = 0; i < n; i++) {
            xNorm[i] = (n == 1) ? 0.5 : (double) i / (n - 1);
        }

        // Normalize Y coordinates (values) to [0, 1]
        double[] yNorm = new double[n];
        double range = lastVal - firstVal;
        for (int i = 0; i < n; i++) {
             Double currentValObj = sortedValues.get(i);
             if (currentValObj == null) { // Handle potential nulls within the list
                 logger.warn("Null value encountered at index {} during normalization.", i);
                 yNorm[i] = Double.NaN; // Represent null as NaN
             } else {
                yNorm[i] = (currentValObj - firstVal) / range;
             }
        }

        // Calculate Perpendicular Distances to the line y = x in normalized space
        double maxNormalizedDistance = -1.0;
        int kneeIndex = -1;

        for (int i = 0; i < n; i++) {
            if (Double.isNaN(yNorm[i])) continue; // Skip points that were null originally

            double normalizedDistance = Math.abs(xNorm[i] - yNorm[i]); // Proportional to distance from y=x

            if (normalizedDistance > maxNormalizedDistance) {
                maxNormalizedDistance = normalizedDistance;
                kneeIndex = i;
            }
        }

        // Return original value at knee index
        if (kneeIndex != -1) {
            Double estimatedValueObj = sortedValues.get(kneeIndex);
            if (estimatedValueObj != null) {
                double estimatedValue = estimatedValueObj;
                logger.info("Knee point identified at index {} with original value: {}", kneeIndex, estimatedValue);
                return estimatedValue;
            } else {
                 logger.error("Internal error: Value at calculated kneeIndex {} is null.", kneeIndex);
                 return -1.0;
            }
        } else {
            logger.warn("Could not identify a knee point (max distance calculation failed?).");
            return -1.0; // Indicate failure
        }
    }
}