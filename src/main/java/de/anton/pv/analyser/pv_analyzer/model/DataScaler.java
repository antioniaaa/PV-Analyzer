package de.anton.pv.analyser.pv_analyzer.model; // Assuming model package

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// No external dependencies needed beyond standard Java and SLF4J

/**
 * Utility class for scaling numerical data using common techniques.
 * Provides methods for Min-Max scaling and Z-Score standardization.
 * Handles NaN values by ignoring them during calculation of scaling parameters
 * and preserving them in the output.
 */
public final class DataScaler {

    private static final Logger logger = LoggerFactory.getLogger(DataScaler.class);
    private static final double EPSILON = 1e-9; // Small value for checking near-zero range/stddev

    // Private constructor to prevent instantiation
    private DataScaler() {
        throw new IllegalStateException("Utility class should not be instantiated.");
    }

    /**
     * Scales the input data using the specified scaling type.
     * Creates a deep copy of the input data before scaling to avoid modifying the original array.
     *
     * @param originalData The 2D array of doubles to scale (rows = samples, cols = features). Can be null or empty.
     * @param type The type of scaling to perform (NONE, MIN_MAX, Z_SCORE).
     * @return A new 2D array containing the scaled data, or the original array if type is NONE or input is invalid/empty. Returns the original array reference on scaling errors.
     */
    public static double[][] scaleData(double[][] originalData, ScalingType type) {
        // Handle null input, empty data, or NONE scaling type: return original data
        if (type == ScalingType.NONE || originalData == null || originalData.length == 0) {
            logger.trace("Scaling skipped: Type is NONE or data is null/empty.");
            return originalData;
        }

        int rows = originalData.length;
        // Check for potential empty rows (though structure implies cols should be consistent)
        if (originalData[0] == null || originalData[0].length == 0) {
             logger.warn("Scaling skipped: Input data has zero columns.");
             return originalData;
        }
        int cols = originalData[0].length;

        // --- Create a Deep Copy ---
        // This is crucial to avoid modifying the caller's original data array.
        double[][] scaledData = new double[rows][];
        for (int i = 0; i < rows; i++) {
            if (originalData[i] == null || originalData[i].length != cols) {
                 logger.error("Scaling error: Inconsistent number of columns at row {}. Expected {}, found {}.",
                             i, cols, (originalData[i] == null ? "null" : originalData[i].length));
                 // Return original data on inconsistent input structure
                 return originalData;
            }
             // Copy each row
             scaledData[i] = new double[cols];
             System.arraycopy(originalData[i], 0, scaledData[i], 0, cols);
        }
        logger.debug("Scaling data with {} rows, {} cols using {} scaling.", rows, cols, type);

        // --- Apply Scaling ---
        try {
            switch (type) {
                case MIN_MAX:
                    return minMaxScale(scaledData); // Operates in-place on the copy
                case Z_SCORE:
                    return zScoreStandardize(scaledData); // Operates in-place on the copy
                default:
                    logger.warn("Unknown scaling type encountered: {}. Returning unscaled data.", type);
                    return scaledData; // Should not happen if enum is used correctly
            }
        } catch (Exception e) {
            // Catch unexpected errors during the scaling process itself
            logger.error("Unexpected error during data scaling ({})", type, e);
            // Return the original (copied) data as a fallback
            return scaledData;
        }
    }

    /**
     * Performs Min-Max scaling (normalization) on the data in-place.
     * Scales each column independently to the range [0, 1].
     * Ignores NaN values when finding min/max. Preserves NaNs in the output.
     * Handles constant columns by scaling them to 0.5.
     *
     * @param data The 2D array (deep copy) to be scaled in-place.
     * @return The same data array reference, now containing scaled values.
     */
    private static double[][] minMaxScale(double[][] data) {
        int rows = data.length;
        if (rows == 0) return data;
        int cols = data[0].length;

        // Scale each column (feature) independently
        for (int j = 0; j < cols; j++) {
            double min = Double.POSITIVE_INFINITY;
            double max = Double.NEGATIVE_INFINITY;
            int validCount = 0; // Count non-NaN values

            // First pass: Find min and max, ignoring NaNs
            for (int i = 0; i < rows; i++) {
                if (!Double.isNaN(data[i][j])) {
                    min = Math.min(min, data[i][j]);
                    max = Math.max(max, data[i][j]);
                    validCount++;
                }
            }

            // Handle cases based on findings
            if (validCount == 0) {
                logger.trace("Min-Max scaling: Column {} contains only NaN values. Skipping.", j);
                continue; // Column remains all NaN (already copied)
            }

            double range = max - min;

            // Handle constant columns (range is effectively zero)
            if (Math.abs(range) < EPSILON) {
                 logger.trace("Min-Max scaling: Column {} is constant (value={}). Setting scaled values to 0.5.", j, min);
                 for (int i = 0; i < rows; i++) {
                     // Only scale the non-NaN values to 0.5
                     if (!Double.isNaN(data[i][j])) {
                         data[i][j] = 0.5; // Common practice for constant columns in [0,1] scaling
                     }
                     // NaNs remain NaN
                 }
            } else {
                // Standard Min-Max scaling for non-constant columns
                // logger.trace("Min-Max scaling column {}: min={}, max={}, range={}", j, min, max, range);
                for (int i = 0; i < rows; i++) {
                     // Apply scaling only to non-NaN values
                     if (!Double.isNaN(data[i][j])) {
                        data[i][j] = (data[i][j] - min) / range;
                    }
                     // NaNs remain NaN
                }
            }
        }
        logger.debug("Min-Max scaling applied successfully.");
        return data; // Return the modified array
    }

    /**
     * Performs Z-Score standardization on the data in-place.
     * Scales each column independently to have a mean of 0 and standard deviation of 1.
     * Ignores NaN values when calculating mean/stddev. Preserves NaNs in the output.
     * Handles constant columns by scaling them to 0.0.
     *
     * @param data The 2D array (deep copy) to be standardized in-place.
     * @return The same data array reference, now containing standardized values.
     */
    private static double[][] zScoreStandardize(double[][] data) {
        int rows = data.length;
        if (rows == 0) return data;
        int cols = data[0].length;

        // Standardize each column (feature) independently
        for (int j = 0; j < cols; j++) {
            double sum = 0;
            double sumSq = 0;
            int count = 0; // Count of non-NaN values

            // First pass: Calculate sum and sum of squares, ignoring NaNs
            for (int i = 0; i < rows; i++) {
                if (!Double.isNaN(data[i][j])) {
                    sum += data[i][j];
                    sumSq += data[i][j] * data[i][j];
                    count++;
                }
            }

            // Handle cases based on findings
            if (count == 0) {
                 logger.trace("Z-Score standardization: Column {} contains only NaN values. Skipping.", j);
                 continue; // Column remains all NaN
            }

            // Calculate mean and standard deviation (using population formula N)
            double mean = sum / count;
            // Variance = E[X^2] - (E[X])^2 = (sumSq / count) - (mean * mean)
            double variance = (sumSq / count) - (mean * mean);

            // Handle potential floating point inaccuracies leading to tiny negative variance
            if (variance < 0 && variance > -EPSILON) {
                 variance = 0.0;
             }

            // If variance is still negative or count is 1 (stddev undefined), cannot standardize
            if (variance < 0 || count < 2) { // StdDev is 0 if count=1, handled below. Check variance < 0.
                 if (variance < 0) {
                     logger.warn("Z-Score standardization: Negative variance ({}) calculated for column {}. Mean={}, SumSq={}, Count={}. Skipping standardization for this column.",
                                 variance, j, mean, sumSq, count);
                 } else { // count < 2 means std dev is 0 or undefined
                     logger.trace("Z-Score standardization: Column {} has {} valid points. StdDev is 0 or undefined. Setting scaled values to 0.0.", j, count);
                     for (int i = 0; i < rows; i++) {
                         if (!Double.isNaN(data[i][j])) data[i][j] = 0.0;
                     }
                 }
                 continue; // Skip to next column
             }

            double stdDev = Math.sqrt(variance);

            // Handle constant columns (stdDev is effectively zero)
            if (Math.abs(stdDev) < EPSILON) {
                 logger.trace("Z-Score standardization: Column {} is constant (stddev~0). Setting standardized values to 0.0.", j);
                 for (int i = 0; i < rows; i++) {
                     // Only standardize the non-NaN values to 0.0
                     if (!Double.isNaN(data[i][j])) {
                         data[i][j] = 0.0;
                    }
                     // NaNs remain NaN
                 }
            } else {
                // Standard Z-Score standardization for non-constant columns
                 // logger.trace("Z-Score standardization column {}: mean={}, stdDev={}", j, mean, stdDev);
                 for (int i = 0; i < rows; i++) {
                     // Apply standardization only to non-NaN values
                     if (!Double.isNaN(data[i][j])) {
                         data[i][j] = (data[i][j] - mean) / stdDev;
                    }
                     // NaNs remain NaN
                 }
            }
        }
         logger.debug("Z-Score standardization applied successfully.");
        return data; // Return the modified array
    }
}