package de.anton.pv.analyser.pv_analyzer.model;

import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;
import de.anton.pv.analyser.pv_analyzer.service.AnalysisService; // Import für AnalysisResult

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The core analysis model holding application data and logic.
 * Supports two analysis modes: single timestamp or finding the maximum
 * scaled vector (Power/Voltage) within a time interval.
 * Runs OPTICS for clustering and DBSCAN (per orientation) for outlier detection.
 * Calculates performance labels based on median specific power.
 */
public class AnalysisModel {
    private static final Logger logger = LoggerFactory.getLogger(AnalysisModel.class);

    public enum AnalysisMode { SINGLE_TIMESTAMP, MAX_VECTOR_INTERVAL }

    public static final String VAR_DC_LEISTUNG = "DC-Leistung (kW)";
    public static final String VAR_SPEZ_LEISTUNG = "Spez. Leistung (kW/kWp)";
    public static final String VAR_DC_SPANNUNG = "DC-Spannung (V)";
    public static final String VAR_STROM_STRING = "Strom/String (A)";
    public static final String VAR_OHM = "Ohm (Ω)";
    public static final List<String> AVAILABLE_VARIABLES = Collections.unmodifiableList(Arrays.asList( VAR_SPEZ_LEISTUNG, VAR_DC_SPANNUNG, VAR_DC_LEISTUNG, VAR_STROM_STRING, VAR_OHM ));
    private static final Map<String, Function<CalculatedDataPoint, Double>> variableExtractors = new HashMap<>();
    static { variableExtractors.put(VAR_DC_LEISTUNG, CalculatedDataPoint::getDcLeistungKW); variableExtractors.put(VAR_SPEZ_LEISTUNG, CalculatedDataPoint::getSpezifischeLeistung); variableExtractors.put(VAR_DC_SPANNUNG, CalculatedDataPoint::getDcSpannungV); variableExtractors.put(VAR_STROM_STRING, CalculatedDataPoint::getStromJeStringA); variableExtractors.put(VAR_OHM, CalculatedDataPoint::getOhm); }
    private static final SimpleDateFormat DATE_FORMAT_PARSER = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    static { DATE_FORMAT_PARSER.setLenient(false); }
    // Data Storage
    private ExcelData excelData = null;
    private File lastLoadedFile = null;
    private List<CalculatedDataPoint> currentAnalysisDataPoints = new ArrayList<>();
    private Map<String, List<CalculatedDataPoint>> processedDataByOrientation = new HashMap<>();

    // Analysis Configuration
    private AnalysisMode currentMode = AnalysisMode.SINGLE_TIMESTAMP;
    private String selectedTimestamp = null;
    private String intervalStartTimestamp = null;
    private String intervalEndTimestamp = null;
    private double opticsEpsilon = 10.0; private int opticsMinPts = 5; private ScalingType opticsScalingType = ScalingType.NONE;
    private double dbscanEpsilon = 0.05; private int dbscanMinPts = 3; private ScalingType dbscanScalingType = ScalingType.MIN_MAX;
    private String selectedXVariable = VAR_SPEZ_LEISTUNG; private String selectedYVariable = VAR_SPEZ_LEISTUNG;
    private Function<CalculatedDataPoint, Double> xExtractor = variableExtractors.get(selectedXVariable);
    private Function<CalculatedDataPoint, Double> yExtractor = variableExtractors.get(selectedYVariable);

    // Results
    private int numberOfClustersFound = 0;
    private boolean outliersWereDetected = false; // Status before potential 50% reset

    private final PropertyChangeSupport support = new PropertyChangeSupport(this);

    public AnalysisModel() { logger.info("AnalysisModel created."); if (xExtractor == null || yExtractor == null) { throw new IllegalStateException("Default variable extractors could not be initialized."); } }
    public void addPropertyChangeListener(PropertyChangeListener pcl) { support.addPropertyChangeListener(pcl); }
    public void removePropertyChangeListener(PropertyChangeListener pcl) { support.removePropertyChangeListener(pcl); }

    /** Updates the model with newly loaded data and the source file reference. */
    public void setDataAndFile(ExcelData newData, File sourceFile) {
        ExcelData oldData = this.excelData;
        this.selectedTimestamp = null; this.intervalStartTimestamp = null; this.intervalEndTimestamp = null;
        clearAnalysisResultsInternal(); // Clear previous results
        this.excelData = newData; this.lastLoadedFile = sourceFile;
        if (newData != null) { logger.info("Model updated with new Excel data from: {}", sourceFile != null ? sourceFile.getName() : "<unknown>"); }
        else { logger.info("Model data cleared."); }
        support.firePropertyChange("excelData", oldData, this.excelData); // Notify listeners
    }

    /** Updates model state based on results from AnalysisService. */
    public void updateAnalysisResults(AnalysisService.AnalysisResult result) {
        // Store old values before updating
        List<CalculatedDataPoint> oldPoints = this.currentAnalysisDataPoints;
        Map<String, List<CalculatedDataPoint>> oldOrientationMap = this.processedDataByOrientation;
        int oldClusterCount = this.numberOfClustersFound;
        boolean oldOutlierStatus = this.outliersWereDetected;

        if (result != null) {
            this.currentAnalysisDataPoints = result.processedDataPoints;
            this.processedDataByOrientation = result.dataByOrientation;
            this.numberOfClustersFound = result.numberOfClusters;
            this.outliersWereDetected = result.outliersFound;
            logger.debug("Model updated with AnalysisResult: {} points, {} orientations, {} clusters, outliers detected: {}",
                         currentAnalysisDataPoints.size(), processedDataByOrientation.size(), numberOfClustersFound, outliersWereDetected);
        } else {
            clearAnalysisResultsInternal(); // Clear if result is null
            logger.debug("Model analysis results cleared due to null result object.");
        }

        // Fire events comparing old and new states
        support.firePropertyChange("processedDataList", oldPoints, getCurrentAnalysisData()); // Use getCurrentAnalysisData for unmodifiable list
        support.firePropertyChange("processedDataMap", oldOrientationMap, getProcessedDataByOrientation());
        if(oldClusterCount != this.numberOfClustersFound) {
             support.firePropertyChange("clusteringResult", oldClusterCount, this.numberOfClustersFound);
        }
         if(oldOutlierStatus != this.outliersWereDetected) {
             support.firePropertyChange("outlierDetectionComplete", oldOutlierStatus, this.outliersWereDetected);
        }
        // No need to fire analysisComplete here, controller does that after calling this
    }


    // --- Getters ---
    public ExcelData getExcelData() { return excelData; }
    
    public List<String> getTimestamps() {
        List<String> ts = excelData != null ? excelData.getTimestamps() : Collections.emptyList();
        logger.debug("Model: getTimestamps() returning list with size: {}", ts.size()); // DEBUG
        return ts;
    }
    
    
    public boolean isDataLoaded() { return excelData != null && !getTimestamps().isEmpty(); }
    public boolean hasModuleInfo() { return excelData != null && excelData.hasModuleInfo(); }
    public ModuleInfo getModuleInfo() { return excelData != null ? excelData.getModuleInfo() : null; }
    public AnalysisMode getCurrentMode() { return currentMode; }
    public String getSelectedTimestamp() { return selectedTimestamp; }
    public String getIntervalStartTimestamp() { return intervalStartTimestamp; }
    public String getIntervalEndTimestamp() { return intervalEndTimestamp; }
    public List<CalculatedDataPoint> getCurrentAnalysisData() { return Collections.unmodifiableList(currentAnalysisDataPoints); }
    public Map<String, List<CalculatedDataPoint>> getProcessedDataByOrientation() { return Collections.unmodifiableMap(processedDataByOrientation); }
    public Set<String> getOrientations() { return processedDataByOrientation != null ? Collections.unmodifiableSet(processedDataByOrientation.keySet()) : Collections.emptySet(); }
    public List<CalculatedDataPoint> getProcessedDataForOrientation(String orientation) { if (orientation == null || processedDataByOrientation == null) return Collections.emptyList(); List<CalculatedDataPoint> data = processedDataByOrientation.get(orientation); return (data != null) ? Collections.unmodifiableList(data) : Collections.emptyList(); }
    public List<CalculatedDataPoint> getAllOutliers() { return currentAnalysisDataPoints.stream().filter(Objects::nonNull).filter(CalculatedDataPoint::isOutlier).collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList)); }
    public double getOpticsEpsilon() { return opticsEpsilon; } public int getOpticsMinPts() { return opticsMinPts; } public ScalingType getOpticsScalingType() { return opticsScalingType; }
    public double getDbscanEpsilon() { return dbscanEpsilon; } public int getDbscanMinPts() { return dbscanMinPts; } public ScalingType getDbscanScalingType() { return dbscanScalingType; }
    public List<String> getAvailableVariables() { return AVAILABLE_VARIABLES; } public String getSelectedXVariable() { return selectedXVariable; } public String getSelectedYVariable() { return selectedYVariable; }
    public Function<CalculatedDataPoint, Double> getXExtractor() { return xExtractor; } public Function<CalculatedDataPoint, Double> getYExtractor() { return yExtractor; }
    public int getNumberOfClusters() { return numberOfClustersFound; }
    public boolean isAnalysisDataAvailable() { boolean configOk = isAnalysisConfigured(); return isDataLoaded() && configOk && !currentAnalysisDataPoints.isEmpty(); }
    public File getLastLoadedFile() { return lastLoadedFile; }

    // --- Setters (Update internal state, trigger events, maybe analysis via Controller) ---
    public void setAnalysisMode(AnalysisMode mode) { if (mode == null) mode = AnalysisMode.SINGLE_TIMESTAMP; if (this.currentMode != mode) { AnalysisMode oldMode = this.currentMode; this.currentMode = mode; logger.info("Model: Analysis mode set to {}", mode); clearAnalysisResultsInternal(); support.firePropertyChange("analysisMode", oldMode, mode); } }
    public void setSelectedTimestamp(String timestamp) { String oldTimestamp = this.selectedTimestamp; boolean changed = !Objects.equals(oldTimestamp, timestamp); if (changed) { this.selectedTimestamp = timestamp; logger.info("Model: Selected timestamp set to '{}'", timestamp); clearAnalysisResultsInternal(); support.firePropertyChange("selectedTimestamp", oldTimestamp, this.selectedTimestamp); } }
    public void setIntervalTimestamps(String start, String end) throws IllegalArgumentException { List<String> availableTimestamps = getTimestamps(); if (start == null || end == null || !availableTimestamps.contains(start) || !availableTimestamps.contains(end)) { throw new IllegalArgumentException("Start- oder End-Zeitstempel ungültig oder nicht in Liste vorhanden."); } Date startDate = parseTimestamp(start); Date endDate = parseTimestamp(end); if (startDate == null || endDate == null || startDate.after(endDate)) { throw new IllegalArgumentException("Start-Zeitstempel muss vor oder gleich dem End-Zeitstempel liegen."); } String oldStart = this.intervalStartTimestamp; String oldEnd = this.intervalEndTimestamp; boolean changed = !Objects.equals(oldStart, start) || !Objects.equals(oldEnd, end); if (changed) { this.intervalStartTimestamp = start; this.intervalEndTimestamp = end; logger.info("Model: Interval set to: {} -> {}", start, end); clearAnalysisResultsInternal(); support.firePropertyChange("intervalTimestamps", new String[]{oldStart, oldEnd}, new String[]{start, end}); } }
    public void setOpticsParameters(double epsilon, int minPts) throws IllegalArgumentException { if (epsilon <= 0 || minPts <= 0) throw new IllegalArgumentException("Params must be positive."); boolean changed = Math.abs(this.opticsEpsilon - epsilon) > 1e-9 || this.opticsMinPts != minPts; if (changed) { double oldEpsilon = this.opticsEpsilon; int oldMinPts = this.opticsMinPts; logger.info("Model: OPTICS params set: eps={}, minPts={}", epsilon, minPts); this.opticsEpsilon = epsilon; this.opticsMinPts = minPts; support.firePropertyChange("opticsParameters", new double[]{oldEpsilon, oldMinPts}, new double[]{epsilon, minPts}); } }
    public void setDbscanParameters(double epsilon, int minPts) throws IllegalArgumentException { if (epsilon <= 0 || minPts <= 0) throw new IllegalArgumentException("Params must be positive."); boolean changed = Math.abs(this.dbscanEpsilon - epsilon) > 1e-9 || this.dbscanMinPts != minPts; if (changed) { double oldEpsilon = this.dbscanEpsilon; int oldMinPts = this.dbscanMinPts; logger.info("Model: DBSCAN params set: eps={}, minPts={}", epsilon, minPts); this.dbscanEpsilon = epsilon; this.dbscanMinPts = minPts; support.firePropertyChange("dbscanParameters", new double[]{oldEpsilon, oldMinPts}, new double[]{epsilon, minPts}); } }
    public void setOpticsScalingType(ScalingType type) { ScalingType newType = (type == null) ? ScalingType.NONE : type; if (this.opticsScalingType != newType) { ScalingType oldType = this.opticsScalingType; this.opticsScalingType = newType; logger.info("Model: OPTICS scaling set: {}", newType); support.firePropertyChange("opticsScalingType", oldType, newType); } }
    public void setDbscanScalingType(ScalingType type) { ScalingType newType = (type == null) ? ScalingType.NONE : type; if (this.dbscanScalingType != newType) { ScalingType oldType = this.dbscanScalingType; this.dbscanScalingType = newType; logger.info("Model: DBSCAN scaling set: {}", newType); support.firePropertyChange("dbscanScalingType", oldType, newType); } }
    public void setOpticsScalingTypeDirect(ScalingType type) { this.opticsScalingType = (type == null) ? ScalingType.NONE : type; } // Direct setter for controller
    public void setDbscanScalingTypeDirect(ScalingType type) { this.dbscanScalingType = (type == null) ? ScalingType.NONE : type; } // Direct setter for controller
    public void setSelectedXVariable(String variableName) { setSelectedVariable(variableName, true); }
    public void setSelectedYVariable(String variableName) { setSelectedVariable(variableName, false); }
    private void setSelectedVariable(String variableName, boolean isX) throws IllegalArgumentException, IllegalStateException { if (variableName == null || !AVAILABLE_VARIABLES.contains(variableName)) throw new IllegalArgumentException("Invalid variable name: " + variableName); String currentVar = isX ? this.selectedXVariable : this.selectedYVariable; if (!variableName.equals(currentVar)) { String oldVar = currentVar; logger.info("Model: Analysis {} variable set: '{}'", isX ? "X" : "Y", variableName); Function<CalculatedDataPoint, Double> newExtractor = variableExtractors.get(variableName); if (newExtractor == null) throw new IllegalStateException("Extractor function not found for variable: " + variableName); if (isX) { this.selectedXVariable = variableName; this.xExtractor = newExtractor; } else { this.selectedYVariable = variableName; this.yExtractor = newExtractor; } support.firePropertyChange("analysisVariables", oldVar, variableName); } }
    public void setSelectedXVariableDirect(String variableName) { if (variableName != null && AVAILABLE_VARIABLES.contains(variableName)) { this.selectedXVariable = variableName; this.xExtractor = variableExtractors.get(variableName); } }
    public void setSelectedYVariableDirect(String variableName) { if (variableName != null && AVAILABLE_VARIABLES.contains(variableName)) { this.selectedYVariable = variableName; this.yExtractor = variableExtractors.get(variableName); } }

    /** Helper to check if analysis can run based on current mode and configuration. */
    public boolean isAnalysisConfigured() { if (!isDataLoaded()) return false; if (currentMode == AnalysisMode.SINGLE_TIMESTAMP) { return selectedTimestamp != null && getTimestamps().contains(selectedTimestamp); } else { if (intervalStartTimestamp == null || intervalEndTimestamp == null || !getTimestamps().contains(intervalStartTimestamp) || !getTimestamps().contains(intervalEndTimestamp)) { return false; } Date startDate = parseTimestamp(intervalStartTimestamp); Date endDate = parseTimestamp(intervalEndTimestamp); return startDate != null && endDate != null && !startDate.after(endDate); } }

    // --- k-Distance Calculation ---
    // Moved to AnalysisService or ParameterEstimationUtils? Keep it here needs createDistanceFunction.
    public List<Double> calculateKDistances(int k, List<CalculatedDataPoint> pointsToAnalyze, ScalingType scalingType, String algorithmName) throws InterruptedException {
        if (pointsToAnalyze == null || pointsToAnalyze.size() <= k) { logger.warn("[{}] Not enough points ({}) for k={}.", algorithmName, pointsToAnalyze != null ? pointsToAnalyze.size() : 0, k); return Collections.emptyList(); }
        if (k <= 0) { throw new IllegalArgumentException("k must be positive for k-distance calculation."); }
        logger.info("[{}] Calculating {}-distance graph for {} points, Scaling: {}", algorithmName, k, pointsToAnalyze.size(), scalingType);
        // Use CURRENT extractors from the model for k-dist calculation
        BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunc;
        try { distanceFunc = createDistanceFunction(pointsToAnalyze, scalingType, this.xExtractor, this.yExtractor, algorithmName + " k-Dist Setup"); }
        catch (Exception e) { logger.error("[{}] Failed to create distance function for k-distance.", algorithmName, e); return Collections.emptyList(); } // Return empty on error

        List<Double> kDistances = new ArrayList<>(pointsToAnalyze.size()); long startTime = System.nanoTime(); boolean useParallel = pointsToAnalyze.size() > 500; IntStream pointIndices = IntStream.range(0, pointsToAnalyze.size()); if (useParallel) { pointIndices = pointIndices.parallel(); }
        List<Double> unsortedKDistances; try { unsortedKDistances = pointIndices .mapToObj(i -> { if (Thread.currentThread().isInterrupted()) { throw new RuntimeException(new InterruptedException("k-distance calculation interrupted."));} CalculatedDataPoint p1 = pointsToAnalyze.get(i); if (p1 == null) return null; List<Double> distancesToOthers = new ArrayList<>(pointsToAnalyze.size() - 1); for (int j = 0; j < pointsToAnalyze.size(); j++) { if (i == j) continue; CalculatedDataPoint p2 = pointsToAnalyze.get(j); if (p2 == null) continue; try { double dist = distanceFunc.apply(p1, p2); if (!Double.isNaN(dist) && !Double.isInfinite(dist)) distancesToOthers.add(dist); } catch (Exception e) { logger.warn("[{}] Error calculating distance between point {} and {} for k-dist: {}", algorithmName, i, j, e.getMessage()); } } if (distancesToOthers.size() >= k) { Collections.sort(distancesToOthers); return distancesToOthers.get(k - 1); } else { return null; } }) .filter(Objects::nonNull).collect(Collectors.toList()); } catch (RuntimeException e) { if (e.getCause() instanceof InterruptedException) { throw (InterruptedException) e.getCause(); } else { throw e; } }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime); logger.debug("[{}] Calculated {} k-distances in {} ms (Parallel={}).", algorithmName, unsortedKDistances.size(), durationMs, useParallel);
        Collections.sort(unsortedKDistances); kDistances.addAll(unsortedKDistances);
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("k-distance calculation interrupted after sorting."); }
        return kDistances;
    }

    // --- Helper Methods ---
    private BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> createDistanceFunction(List<CalculatedDataPoint> points, ScalingType scalingType, Function<CalculatedDataPoint, Double> xExtractorFunc, Function<CalculatedDataPoint, Double> yExtractorFunc, String algorithmName) {
         logger.debug("Model [{}]: Creating distance function, Scaling='{}'", algorithmName, scalingType); if (xExtractorFunc == null || yExtractorFunc == null) throw new IllegalStateException("Extractors null"); if (scalingType == ScalingType.NONE) { return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; try { double x1 = xExtractorFunc.apply(p1); double y1 = yExtractorFunc.apply(p1); double x2 = xExtractorFunc.apply(p2); double y2 = yExtractorFunc.apply(p2); if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (Exception e) { logger.warn("Model [{}] Error extracting data (unscaled): {}", algorithmName, e.getMessage()); return Double.POSITIVE_INFINITY; } }; }
         logger.debug("Model [{}]: Preparing data for {} scaling.", algorithmName, scalingType); int nPoints = points.size(); if (nPoints == 0) return (p1, p2) -> Double.POSITIVE_INFINITY; double[][] rawData = new double[nPoints][2]; Map<CalculatedDataPoint, Integer> pointToIndex = new HashMap<>(nPoints); for (int i = 0; i < nPoints; i++) { CalculatedDataPoint p = points.get(i); if (p == null) { rawData[i][0] = Double.NaN; rawData[i][1] = Double.NaN; continue; } try { rawData[i][0] = xExtractorFunc.apply(p); rawData[i][1] = yExtractorFunc.apply(p); pointToIndex.put(p, i); } catch (Exception e) { logger.warn("Model [{}] Error extracting data for scaling prep for {}: {}", algorithmName, p.getName(), e.getMessage()); rawData[i][0] = Double.NaN; rawData[i][1] = Double.NaN; } }
         double[][] scaledData; try { scaledData = DataScaler.scaleData(rawData, scalingType); if (scaledData == rawData && scalingType != ScalingType.NONE) { logger.warn("Model [{}]: Scaling type {} no change. Falling back to unscaled.", algorithmName, scalingType); return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; try { double x1 = xExtractorFunc.apply(p1); double y1 = yExtractorFunc.apply(p1); double x2 = xExtractorFunc.apply(p2); double y2 = yExtractorFunc.apply(p2); if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (Exception e) { return Double.POSITIVE_INFINITY; } }; } logger.debug("Model [{}]: Data scaling ({}) completed.", algorithmName, scalingType); } catch (Exception e) { logger.error("Model [{}]: Error during data scaling ({}).", algorithmName, scalingType, e); throw new RuntimeException("Fehler bei der Datenskalierung (" + scalingType + "): " + e.getMessage(), e); }
         final double[][] finalScaledData = scaledData; return (p1, p2) -> { if (p1 == null || p2 == null) return Double.POSITIVE_INFINITY; Integer idx1 = pointToIndex.get(p1); Integer idx2 = pointToIndex.get(p2); if (idx1 == null || idx2 == null || idx1 < 0 || idx1 >= finalScaledData.length || idx2 < 0 || idx2 >= finalScaledData.length) { return Double.POSITIVE_INFINITY; } try { double x1 = finalScaledData[idx1][0]; double y1 = finalScaledData[idx1][1]; double x2 = finalScaledData[idx2][0]; double y2 = finalScaledData[idx2][1]; if (Double.isNaN(x1) || Double.isNaN(y1) || Double.isNaN(x2) || Double.isNaN(y2)) return Double.POSITIVE_INFINITY; double dx = x1 - x2; double dy = y1 - y2; return Math.sqrt(dx * dx + dy * dy); } catch (ArrayIndexOutOfBoundsException e) { logger.error("Model [{}] Array index out of bounds ({},{}) in scaled distance calc.", algorithmName, idx1, idx2, e); return Double.POSITIVE_INFINITY; } };
    }
    private Date parseTimestamp(String timestampStr) { if (timestampStr == null) return null; try { synchronized (DATE_FORMAT_PARSER) { return DATE_FORMAT_PARSER.parse(timestampStr); } } catch (ParseException e) { logger.warn("Could not parse timestamp string: {}", timestampStr, e); return null; } }

    /** Clears only the results of the last analysis run. */
    private void clearAnalysisResultsInternal() {
        boolean dataExisted = !currentAnalysisDataPoints.isEmpty();
        // Store old values before clearing
        Map<String, List<CalculatedDataPoint>> oldProcessedMap = this.processedDataByOrientation;
        List<CalculatedDataPoint> oldAllProcessedList = this.currentAnalysisDataPoints;
        int oldClusterCount = this.numberOfClustersFound;
        boolean oldOutlierStatus = this.outliersWereDetected;

        // Clear current results
        this.processedDataByOrientation = new HashMap<>();
        this.currentAnalysisDataPoints = new ArrayList<>();
        this.numberOfClustersFound = 0;
        this.outliersWereDetected = false;

        if (dataExisted) {
            logger.debug("Cleared analysis results (data points, orientations, cluster count, outlier status).");
            // Fire property changes using the stored old values
            support.firePropertyChange("processedDataMap", oldProcessedMap, Collections.unmodifiableMap(this.processedDataByOrientation));
            support.firePropertyChange("processedDataList", oldAllProcessedList, Collections.unmodifiableList(this.currentAnalysisDataPoints));
            support.firePropertyChange("clusteringResult", oldClusterCount, 0);
            support.firePropertyChange("outlierDetectionComplete", oldOutlierStatus, false);
        }
    }

    // Keep clearClusteringResults and clearOutlierResults for potential finer control,
    // but ensure they ONLY modify flags on existing points, not clear lists.
    private void clearClusteringResults() {
         int oldClusterCount = this.numberOfClustersFound;
         if (oldClusterCount != 0 || !currentAnalysisDataPoints.isEmpty()) {
            this.numberOfClustersFound = 0;
            currentAnalysisDataPoints.forEach(p -> { if (p != null) p.setClusterGroup(MyOPTICS.NOISE); });
            logger.debug("Clustering results cleared (flags on points).");
             if(oldClusterCount != 0) { support.firePropertyChange("clusteringResult", oldClusterCount, 0); }
         }
         this.numberOfClustersFound = 0; // Ensure zero
    }
    private void clearOutlierResults() {
         AtomicBoolean stateChanged = new AtomicBoolean(false);
         for (CalculatedDataPoint p : currentAnalysisDataPoints) { // Iterate existing points
             if (p != null) {
                 if (p.isOutlier()) { p.setOutlier(false); stateChanged.set(true); }
                 if (!p.getPerformanceLabel().isEmpty()) { p.setPerformanceLabel(""); stateChanged.set(true); }
             }
         }
         if (stateChanged.get()) { logger.debug("Outlier flags and performance labels reset on existing points."); }
         // Do not fire outlierDetectionComplete here, let analysis completion handle it
    }
}