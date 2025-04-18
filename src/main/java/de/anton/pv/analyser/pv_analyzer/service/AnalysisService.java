package de.anton.pv.analyser.pv_analyzer.service; // Beispiel-Package

import de.anton.pv.analyser.pv_analyzer.model.*;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel.AnalysisMode;
import de.anton.pv.analyser.pv_analyzer.algorithms.MyDBSCAN;
import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Service responsible for performing the core data analysis steps:
 * data preparation, clustering, outlier detection, and performance labeling.
 * This class is designed to be stateless or hold only temporary data for a single run.
 */
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);
    private static final double MIN_POWER_THRESHOLD_KW = 0.05;//min 0.05 kW DC-Leistung
    private static final double MIN_MAX_EPSILON = 1e-9;
    private static final SimpleDateFormat DATE_FORMAT_PARSER = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    static { DATE_FORMAT_PARSER.setLenient(false); }


    /**
     * Represents the comprehensive result of a full analysis run.
     * Includes processed data, cluster count, and outlier status.
     */
    public static class AnalysisResult {
        public final List<CalculatedDataPoint> processedDataPoints;
        public final Map<String, List<CalculatedDataPoint>> dataByOrientation;
        public final int numberOfClusters;
        public final boolean outliersFound; // True if *any* valid outliers were detected before threshold check

        // Private constructor to force usage of builder or factory method if needed
        private AnalysisResult(List<CalculatedDataPoint> data, Map<String, List<CalculatedDataPoint>> byOrientation, int clusters, boolean outliersFound) {
            this.processedDataPoints = data != null ? Collections.unmodifiableList(data) : Collections.emptyList();
            this.dataByOrientation = byOrientation != null ? Collections.unmodifiableMap(byOrientation) : Collections.emptyMap();
            this.numberOfClusters = clusters;
            this.outliersFound = outliersFound;
        }
    }

    /**
     * Executes the complete analysis pipeline based on the provided configuration.
     *
     * @param config The AnalysisConfiguration containing all necessary data and parameters.
     * @return An AnalysisResult object containing the results.
     * @throws InterruptedException If the process is interrupted.
     * @throws Exception For other analysis errors.
     */
    public AnalysisResult runFullAnalysis(AnalysisConfiguration config) throws InterruptedException, Exception {
        String configDesc = (config.mode() == AnalysisMode.SINGLE_TIMESTAMP) ? "Timestamp: " + config.timestamp() : "Interval: " + config.intervalStart() + " -> " + config.intervalEnd();
        logger.info("Service: Starting full analysis process (Mode: {}, X={}, Y={}) for {}.",
                    config.mode(), config.selectedXVarName(), config.selectedYVarName(), configDesc);

        // 1. Prepare Data based on mode
        List<CalculatedDataPoint> preparedData = prepareAnalysisData(
                config.excelData(), config.mode(), config.timestamp(), config.intervalStart(), config.intervalEnd());

        if (preparedData.isEmpty()) {
             logger.warn("Service: Analysis aborted: No processable data found after processing for mode {}.", config.mode());
             return new AnalysisResult(Collections.emptyList(), Collections.emptyMap(), 0, false);
        }

        Map<String, List<CalculatedDataPoint>> dataByOrientation = groupDataByOrientation(preparedData);
        preparedData.forEach(p -> { if (p != null) { p.setClusterGroup(MyOPTICS.NOISE); p.setOutlier(false); p.setPerformanceLabel(""); } });

        int clusterCount = 0;
        boolean outliersWereFound = false;

        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Analysis cancelled before clustering.");
            clusterCount = performOpticsClustering( preparedData, config.opticsEpsilon(), config.opticsMinPts(), config.opticsScalingType(), config.xExtractor(), config.yExtractor(), config.selectedXVarName(), config.selectedYVarName());
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Analysis cancelled before outlier detection.");
            outliersWereFound = performDbscanOutlierDetection( dataByOrientation, config.dbscanEpsilon(), config.dbscanMinPts(), config.dbscanScalingType(), config.xExtractor(), config.yExtractor(), config.selectedXVarName(), config.selectedYVarName());
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Analysis cancelled before performance labelling.");
            calculateAndSetPerformanceLabels(dataByOrientation);
            logger.info("Service: Full analysis process completed successfully.");
        } catch (InterruptedException e) {
            logger.info("Service: Analysis process was interrupted.");
            preparedData.forEach(p -> { if (p != null) { p.setClusterGroup(MyOPTICS.NOISE); p.setOutlier(false); p.setPerformanceLabel(""); } });
            throw e; // Re-throw
        } catch (Exception e) {
            logger.error("Service: Error during analysis execution", e);
            preparedData.forEach(p -> { if (p != null) { p.setClusterGroup(MyOPTICS.NOISE); p.setOutlier(false); p.setPerformanceLabel(""); } });
            throw e; // Re-throw
        }

        return new AnalysisResult(preparedData, dataByOrientation, clusterCount, outliersWereFound);
    }


    /** Prepares data based on the selected mode. */
    private List<CalculatedDataPoint> prepareAnalysisData(
            ExcelData excelData, AnalysisMode mode, String timestamp, String intervalStart, String intervalEnd)
            throws InterruptedException, IllegalArgumentException {

        // *** KORREKTUR: Überprüfe excelData und Timestamps direkt ***
        if (excelData == null || excelData.getTimestamps() == null || excelData.getTimestamps().isEmpty()) {
            logger.warn("Service: Cannot prepare data: Excel data not loaded or contains no timestamps.");
            return Collections.emptyList();
        }

        if (mode == AnalysisMode.SINGLE_TIMESTAMP) {
            if (timestamp == null || !excelData.getTimestamps().contains(timestamp)) {
                throw new IllegalArgumentException("Invalid or missing timestamp for SINGLE_TIMESTAMP mode.");
            }
            return processDataForSingleTimestamp(excelData, timestamp);
        } else { // MAX_VECTOR_INTERVAL
             if (intervalStart == null || intervalEnd == null ||
                 !excelData.getTimestamps().contains(intervalStart) ||
                 !excelData.getTimestamps().contains(intervalEnd)) {
                throw new IllegalArgumentException("Invalid or missing interval timestamps.");
            }
             Date startDate = parseTimestamp(intervalStart);
             Date endDate = parseTimestamp(intervalEnd);
             if (startDate == null || endDate == null || startDate.after(endDate)) {
                throw new IllegalArgumentException("Interval start must be before or equal to end.");
            }
            return processDataForIntervalMaxVector(excelData, intervalStart, intervalEnd);
        }
    }


    /** Processes data for a single timestamp. */
    private List<CalculatedDataPoint> processDataForSingleTimestamp(ExcelData excelData, String timestamp) {
         logger.debug("Service: Processing raw data for timestamp: {}", timestamp);
         Map<String, Double> dataRow = excelData.getDataRowForTimestamp(timestamp);
         if (dataRow == null || dataRow.isEmpty()) { logger.warn("Service: No data found for timestamp: {}", timestamp); return Collections.emptyList(); }
         ModuleInfo modInfo = excelData.getModuleInfo(); Map<String, TrackerInfo> trackerInfoMap = excelData.getTrackerInfoMap();
         if (trackerInfoMap == null || trackerInfoMap.isEmpty()) { logger.error("Service: Tracker information missing."); return Collections.emptyList(); }
         List<CalculatedDataPoint> processedPoints = new ArrayList<>();
         for (Map.Entry<String, TrackerInfo> entry : trackerInfoMap.entrySet()) {
             String trackerName = entry.getKey(); TrackerInfo trackerInfo = entry.getValue(); if (trackerInfo == null) continue;
             String powerKey = trackerName + "/DC-Leistung(kW)"; String voltageKey = trackerName + "/DC-Spannung(V)";
             Double powerKW = dataRow.getOrDefault(powerKey, Double.NaN); Double voltageV = dataRow.getOrDefault(voltageKey, Double.NaN);
             processedPoints.add(new CalculatedDataPoint(trackerName, powerKW, voltageV, trackerInfo, modInfo, timestamp));
         }
         processedPoints.sort(Comparator.comparing(CalculatedDataPoint::getName, Comparator.nullsLast(String::compareTo)));
         return processedPoints;
     }

     /** Processes data for interval max vector mode (including scaling). */
     private List<CalculatedDataPoint> processDataForIntervalMaxVector(ExcelData excelData, String intervalStart, String intervalEnd) throws InterruptedException {
         logger.debug("Service: Processing data for interval (Max Scaled Vector): {} -> {}", intervalStart, intervalEnd);
         List<String> allTimestamps = excelData.getTimestamps(); List<Map<String, Double>> allDataRows = excelData.getSheet1Data(); ModuleInfo modInfo = excelData.getModuleInfo(); Map<String, TrackerInfo> trackerInfoMap = excelData.getTrackerInfoMap();
         if (trackerInfoMap == null || trackerInfoMap.isEmpty()) { logger.error("Service: Tracker information missing for interval."); return Collections.emptyList(); }
         int startIndex = allTimestamps.indexOf(intervalStart); int endIndex = allTimestamps.indexOf(intervalEnd);
         if (startIndex == -1 || endIndex == -1 || startIndex > endIndex) { logger.error("Service: Invalid interval indices: start={}, end={}", startIndex, endIndex); return Collections.emptyList(); }

         double globalMinPower = Double.POSITIVE_INFINITY; double globalMaxPower = Double.NEGATIVE_INFINITY; double globalMinVoltage = Double.POSITIVE_INFINITY; double globalMaxVoltage = Double.NEGATIVE_INFINITY; boolean foundValidData = false;
         logger.debug("Service: Pass 1: Finding Min/Max Power and Voltage in interval...");
         for (int i = startIndex; i <= endIndex; i++) { if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Interval Pass 1 interrupted."); Map<String, Double> dataRow = allDataRows.get(i); for (String trackerName : trackerInfoMap.keySet()) { String powerKey = trackerName + "/DC-Leistung(kW)"; String voltageKey = trackerName + "/DC-Spannung(V)"; Double powerKW = dataRow.getOrDefault(powerKey, Double.NaN); Double voltageV = dataRow.getOrDefault(voltageKey, Double.NaN); if (!Double.isNaN(powerKW) && !Double.isNaN(voltageV) && powerKW > MIN_POWER_THRESHOLD_KW) { globalMinPower = Math.min(globalMinPower, powerKW); globalMaxPower = Math.max(globalMaxPower, powerKW); globalMinVoltage = Math.min(globalMinVoltage, voltageV); globalMaxVoltage = Math.max(globalMaxVoltage, voltageV); foundValidData = true; } } }
         if (!foundValidData) { logger.warn("Service: No valid data points found in interval meeting power threshold."); return Collections.emptyList(); }
         double powerRange = globalMaxPower - globalMinPower; double voltageRange = globalMaxVoltage - globalMinVoltage; boolean powerIsConstant = Math.abs(powerRange) < MIN_MAX_EPSILON; boolean voltageIsConstant = Math.abs(voltageRange) < MIN_MAX_EPSILON;

         logger.debug("Service: Pass 2: Finding max scaled vector point per tracker...");
         List<CalculatedDataPoint> maxVectorPoints = new ArrayList<>();
         for (Map.Entry<String, TrackerInfo> trackerEntry : trackerInfoMap.entrySet()) {
             if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Interval Pass 2 interrupted for tracker " + trackerEntry.getKey());
             String trackerName = trackerEntry.getKey(); TrackerInfo trackerInfo = trackerEntry.getValue(); if (trackerInfo == null) continue;
             double maxScaledVectorLengthSq = -1.0; 
             double bestRawPower = Double.NaN; 
             double bestRawVoltage = Double.NaN; 
             String bestTimestamp = null;
             for (int i = startIndex; i <= endIndex; i++) { 
            	 Map<String, Double> dataRow = allDataRows.get(i); 
            	 String currentTsString = allTimestamps.get(i); 
            	 String powerKey = trackerName + "/DC-Leistung(kW)"; 
            	 String voltageKey = trackerName + "/DC-Spannung(V)"; 
            	 Double powerKW = dataRow.getOrDefault(powerKey, Double.NaN); 
            	 //because of variability in the Variable names
            	 if(Double.isNaN(powerKW)) {
            		 powerKey = trackerName + " /DC-Leistung(kW)"; 
            		 powerKW = dataRow.getOrDefault(powerKey, Double.NaN); 
            	 }
            	 Double voltageV = dataRow.getOrDefault(voltageKey, Double.NaN);
            	 if(Double.isNaN(voltageV)) {
            		 voltageKey = trackerName + " /DC-Spannung(V)"; 
            		 voltageV = dataRow.getOrDefault(voltageKey, Double.NaN);
            	 }
            	 
            	 if(trackerName.equals("TR#24.4")) {
            		 System.out.println(trackerName+": "+ powerKW+"; "+voltageV);
            	 }
            	 
                 if (!Double.isNaN(powerKW) && !Double.isNaN(voltageV) && powerKW > MIN_POWER_THRESHOLD_KW) {
                	 
                	 //TR#02.1 /DC-Leistung(kW)
                	 //TR#01.4/DC-Leistung(kW)
                	 
                     double scaledPower = powerIsConstant ? 0.5 : ((powerRange < MIN_MAX_EPSILON) ? 0.5 : (powerKW - globalMinPower) / powerRange);
                     //double scaledVoltage = voltageIsConstant ? 0.5 : ((voltageRange < MIN_MAX_EPSILON) ? 0.5 : (voltageV - globalMinVoltage) / voltageRange);
                     
                     /** my simplification to analyse just the power value*/ 
                     double currentScaledVectorLengthSq = scaledPower; //(scaledPower * scaledPower) + (scaledVoltage * scaledVoltage);
                     
                     
                     if (currentScaledVectorLengthSq > maxScaledVectorLengthSq) { 
                    	 maxScaledVectorLengthSq = currentScaledVectorLengthSq; 
                    	 bestRawPower = powerKW; 
                    	 bestRawVoltage = voltageV; 
                    	 bestTimestamp = currentTsString; 
                    	 }
                 }
             }
             if (bestTimestamp != null) { 
            	 maxVectorPoints.add(new CalculatedDataPoint( trackerName, bestRawPower, bestRawVoltage, trackerInfo, modInfo, bestTimestamp )); 
             } else { 
            	 logger.warn("Service: No valid point meeting criteria found for tracker {} in interval.", trackerName); 
             }
         }
         maxVectorPoints.sort(Comparator.comparing(CalculatedDataPoint::getName, Comparator.nullsLast(String::compareTo)));
         return maxVectorPoints;
      }

    /** Groups the prepared data points by orientation. */
    private Map<String, List<CalculatedDataPoint>> groupDataByOrientation(List<CalculatedDataPoint> points) { return points.stream().filter(p -> p != null && p.getAusrichtung() != null) .collect(Collectors.groupingBy( CalculatedDataPoint::getAusrichtung, LinkedHashMap::new, Collectors.toList() )); }

    /** Performs OPTICS clustering. */
    private int performOpticsClustering(List<CalculatedDataPoint> dataPoints, double epsilon, int minPts, ScalingType scalingType, Function<CalculatedDataPoint, Double> xExtractor, Function<CalculatedDataPoint, Double> yExtractor, String selectedXVarName, String selectedYVarName) throws InterruptedException { logger.info("Service: Starting OPTICS clustering (X={}, Y={}, Scale={})", selectedXVarName, selectedYVarName, scalingType); if (dataPoints == null || dataPoints.isEmpty()) { logger.warn("Service: OPTICS skipped, no data points."); return 0; } if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS cancelled before starting."); List<CalculatedDataPoint> validPoints = dataPoints.stream().filter(p -> p != null && !Double.isNaN(xExtractor.apply(p)) && !Double.isNaN(yExtractor.apply(p))).collect(Collectors.toList()); logger.debug("Service: Found {} valid points for OPTICS.", validPoints.size()); if (validPoints.size() < minPts) { logger.warn("Service: OPTICS skipped: Not enough valid points ({}) < minPts ({}).", validPoints.size(), minPts); return 0; } BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunc; try { distanceFunc = createDistanceFunction(validPoints, scalingType, xExtractor, yExtractor, "OPTICS"); } catch (Exception e) { logger.error("Service: Failed to create distance function for OPTICS.", e); throw new RuntimeException("Fehler bei Distanzfunktion-Erstellung (OPTICS)", e); } MyOPTICS optics = new MyOPTICS(validPoints, epsilon, minPts, distanceFunc); optics.run(); int clusterCount = (int) validPoints.stream().mapToInt(CalculatedDataPoint::getClusterGroup).filter(id -> id >= 0).distinct().count(); logger.info("Service: OPTICS finished, found {} clusters.", clusterCount); return clusterCount; }

    /** Performs DBSCAN outlier detection per orientation group. */
    private boolean performDbscanOutlierDetection(Map<String, List<CalculatedDataPoint>> dataByOrientation, double epsilon, int minPts, ScalingType scalingType, Function<CalculatedDataPoint, Double> xExtractor, Function<CalculatedDataPoint, Double> yExtractor, String selectedXVarName, String selectedYVarName) throws InterruptedException { logger.info("Service: Starting DBSCAN outlier detection per orientation (X={}, Y={}, Scale={})", selectedXVarName, selectedYVarName, scalingType); if (dataByOrientation == null || dataByOrientation.isEmpty()) { logger.warn("Service: DBSCAN skipped, no data by orientation."); return false; } if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled before orientation loop."); logger.info("Service: Running DBSCAN per orientation: ε={}, minPts={}", epsilon, minPts); boolean anyOutliersFoundOverall = false; for (Map.Entry<String, List<CalculatedDataPoint>> entry : dataByOrientation.entrySet()) { String orientation = entry.getKey(); List<CalculatedDataPoint> orientationData = entry.getValue(); if (orientationData == null || orientationData.isEmpty()) continue; if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled during loop for orientation " + orientation); List<CalculatedDataPoint> validPoints = orientationData.stream().filter(p -> p != null && !Double.isNaN(xExtractor.apply(p)) && !Double.isNaN(yExtractor.apply(p))).collect(Collectors.toList()); logger.trace("Service: Orientation '{}': Found {} valid points for DBSCAN.", orientation, validPoints.size()); if (validPoints.size() < minPts) { logger.info("Service: Skipping DBSCAN for orientation '{}': {} valid points < minPts ({}).", orientation, validPoints.size(), minPts); continue; } BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunc; try { distanceFunc = createDistanceFunction(validPoints, scalingType, xExtractor, yExtractor, "DBSCAN (Orientation: " + orientation + ")"); } catch (Exception e) { logger.error("Service: Failed to create distance function for DBSCAN orientation '{}'. Skipping.", orientation, e); continue; } MyDBSCAN dbscan = null; long groupOutlierCount = 0; boolean groupHadValidOutliers = false; try { logger.debug("Service: Running DBSCAN for orientation '{}'...", orientation); long startTime = System.currentTimeMillis(); dbscan = new MyDBSCAN(validPoints, epsilon, minPts, distanceFunc); dbscan.run(); long duration = System.currentTimeMillis() - startTime; groupOutlierCount = validPoints.stream().filter(CalculatedDataPoint::isOutlier).count(); logger.debug("Service: Orientation '{}': DBSCAN finished in {} ms. Found {} potential outliers.", orientation, duration, groupOutlierCount); if (groupOutlierCount * 2 > validPoints.size()) { logger.warn("Service: Orientation '{}': More than 50% outliers ({}/{}) detected. Discarding labels.", orientation, groupOutlierCount, validPoints.size()); validPoints.forEach(p -> p.setOutlier(false)); } else if (groupOutlierCount > 0) { groupHadValidOutliers = true; } } catch (InterruptedException e) { logger.info("Service: DBSCAN execution interrupted for orientation '{}'.", orientation); validPoints.forEach(p -> { if (p != null) p.setOutlier(false); }); throw e; } catch (Exception e) { logger.error("Service: Error during DBSCAN execution for orientation '{}'", orientation, e); validPoints.forEach(p -> { if (p != null) p.setOutlier(false); }); /* Continue? */ } if (groupHadValidOutliers) { anyOutliersFoundOverall = true; } } logger.info("Service: DBSCAN outlier detection finished. Any valid outliers found overall: {}", anyOutliersFoundOverall); return anyOutliersFoundOverall; }

    /** Calculates and sets performance labels. */
    private void calculateAndSetPerformanceLabels(Map<String, List<CalculatedDataPoint>> dataByOrientation) { logger.debug("Service: Calculating performance labels..."); if (dataByOrientation == null || dataByOrientation.isEmpty()) { logger.warn("Service: Cannot calculate performance labels: No data grouped by orientation."); return; } AtomicBoolean labelsChanged = new AtomicBoolean(false); for (Map.Entry<String, List<CalculatedDataPoint>> entry : dataByOrientation.entrySet()) { String orientation = entry.getKey(); List<CalculatedDataPoint> pointsInOrientation = entry.getValue(); List<Double> specificPowers = pointsInOrientation.stream().map(CalculatedDataPoint::getSpezifischeLeistung).filter(val -> val != null && !Double.isNaN(val)).sorted().collect(Collectors.toList()); if (specificPowers.isEmpty()) { logger.warn("Service: No valid specific power values for orientation '{}'.", orientation); pointsInOrientation.forEach(p -> { if(p != null && !p.getPerformanceLabel().isEmpty()) { p.setPerformanceLabel(""); labelsChanged.set(true);} }); continue; } double medianSpecificPower; int n = specificPowers.size(); if (n % 2 == 1) { medianSpecificPower = specificPowers.get(n / 2); } else { medianSpecificPower = (specificPowers.get(n / 2 - 1) + specificPowers.get(n / 2)) / 2.0; } logger.trace("Service: Orientation '{}': Median Specific Power = {}", orientation, String.format("%.4f", medianSpecificPower)); for (CalculatedDataPoint point : pointsInOrientation) { if (point == null) continue; double pointSpecificPower = point.getSpezifischeLeistung(); String newLabel = ""; if (!Double.isNaN(pointSpecificPower)) { if (pointSpecificPower > medianSpecificPower + 1e-9) { newLabel = "hoch"; } else if (pointSpecificPower < medianSpecificPower - 1e-9) { newLabel = "niedrig"; } else { newLabel = "median"; } } if (!Objects.equals(point.getPerformanceLabel(), newLabel)) { point.setPerformanceLabel(newLabel); labelsChanged.set(true); } } } logger.debug("Service: Performance labels calculation finished. Labels changed: {}", labelsChanged.get()); }

    /** Helper to create distance function. */
    private BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> createDistanceFunction(List<CalculatedDataPoint> points, ScalingType scalingType, Function<CalculatedDataPoint, Double> xExtractor, Function<CalculatedDataPoint, Double> yExtractor, String algorithmName) { logger.debug("Service [{}]: Creating distance function, Scaling='{}'", algorithmName, scalingType); if (xExtractor == null || yExtractor == null) throw new IllegalStateException("Extractors null"); if (scalingType == ScalingType.NONE) { return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; try { double x1 = xExtractor.apply(p1); double y1 = yExtractor.apply(p1); double x2 = xExtractor.apply(p2); double y2 = yExtractor.apply(p2); if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (Exception e) { logger.warn("Service [{}] Error extracting data (unscaled): {}", algorithmName, e.getMessage()); return Double.POSITIVE_INFINITY; } }; } logger.debug("Service [{}]: Preparing data for {} scaling.", algorithmName, scalingType); int nPoints = points.size(); if (nPoints == 0) return (p1, p2) -> Double.POSITIVE_INFINITY; double[][] rawData = new double[nPoints][2]; Map<CalculatedDataPoint, Integer> pointToIndex = new HashMap<>(nPoints); for (int i = 0; i < nPoints; i++) { CalculatedDataPoint p = points.get(i); if (p == null) { rawData[i][0] = Double.NaN; rawData[i][1] = Double.NaN; continue; } try { rawData[i][0] = xExtractor.apply(p); rawData[i][1] = yExtractor.apply(p); pointToIndex.put(p, i); } catch (Exception e) { logger.warn("Service [{}] Error extracting data for scaling prep for {}: {}", algorithmName, p.getName(), e.getMessage()); rawData[i][0] = Double.NaN; rawData[i][1] = Double.NaN; } } double[][] scaledData; try { scaledData = DataScaler.scaleData(rawData, scalingType); if (scaledData == rawData && scalingType != ScalingType.NONE) { logger.warn("Service [{}]: Scaling type {} no change. Falling back to unscaled.", algorithmName, scalingType); return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; try { double x1 = xExtractor.apply(p1); double y1 = yExtractor.apply(p1); double x2 = xExtractor.apply(p2); double y2 = yExtractor.apply(p2); if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (Exception e) { return Double.POSITIVE_INFINITY; } }; } logger.debug("Service [{}]: Data scaling ({}) completed.", algorithmName, scalingType); } catch (Exception e) { logger.error("Service [{}]: Error during data scaling ({}).", algorithmName, scalingType, e); throw new RuntimeException("Fehler bei der Datenskalierung (" + scalingType + "): " + e.getMessage(), e); } final double[][] finalScaledData = scaledData; return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; Integer idx1 = pointToIndex.get(p1); Integer idx2 = pointToIndex.get(p2); if (idx1 == null || idx2 == null || idx1 < 0 || idx1 >= finalScaledData.length || idx2 < 0 || idx2 >= finalScaledData.length) { return Double.POSITIVE_INFINITY; } try { double x1 = finalScaledData[idx1][0]; double y1 = finalScaledData[idx1][1]; double x2 = finalScaledData[idx2][0]; double y2 = finalScaledData[idx2][1]; if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (ArrayIndexOutOfBoundsException e) { logger.error("Service [{}] Array index out of bounds ({},{}) in scaled distance calc.", algorithmName, idx1, idx2, e); return Double.POSITIVE_INFINITY; } }; }
    private Date parseTimestamp(String timestampStr) { if (timestampStr == null) return null; try { synchronized (DATE_FORMAT_PARSER) { return DATE_FORMAT_PARSER.parse(timestampStr); } } catch (ParseException e) { logger.warn("Could not parse timestamp string: {}", timestampStr, e); return null; } }
}