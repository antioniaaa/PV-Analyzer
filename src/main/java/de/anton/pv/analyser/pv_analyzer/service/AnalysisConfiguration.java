package de.anton.pv.analyser.pv_analyzer.service;

import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel.AnalysisMode;
import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import de.anton.pv.analyser.pv_analyzer.model.ExcelData;
import de.anton.pv.analyser.pv_analyzer.model.ScalingType;

import java.util.function.Function;

/**
 * Immutable configuration object holding all parameters for an analysis run.
 */
public record AnalysisConfiguration(
    ExcelData excelData,
    AnalysisMode mode,
    String timestamp, // Used for SINGLE_TIMESTAMP mode
    String intervalStart, // Used for MAX_VECTOR_INTERVAL mode
    String intervalEnd,   // Used for MAX_VECTOR_INTERVAL mode
    double opticsEpsilon,
    int opticsMinPts,
    ScalingType opticsScalingType,
    double dbscanEpsilon,
    int dbscanMinPts,
    ScalingType dbscanScalingType,
    String selectedXVarName,
    String selectedYVarName,
    Function<CalculatedDataPoint, Double> xExtractor,
    Function<CalculatedDataPoint, Double> yExtractor
) {
    // No additional methods needed for a simple record
}