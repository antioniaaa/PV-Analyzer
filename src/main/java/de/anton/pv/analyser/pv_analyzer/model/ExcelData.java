package de.anton.pv.analyser.pv_analyzer.model;

import java.util.*; // Required for List, Map, Collections etc.

/**
 * Data Transfer Object (DTO) to hold all data extracted from the Excel file.
 * Provides methods to access timestamps, headers, time-series data, tracker information,
 * and module information. Emphasizes immutability where appropriate by returning
 * unmodifiable collections or copies from getters.
 */
public class ExcelData {
    // Use defensive copying in setters and unmodifiable views in getters
    private List<String> timestamps = Collections.emptyList();
    private List<String> sheet1Headers = Collections.emptyList();
    // List of rows, where each row is a map from Header(String) to Value(Double)
    private List<Map<String, Double>> sheet1Data = Collections.emptyList();
    // Map from TrackerName(String) to TrackerInfo object
    private Map<String, TrackerInfo> trackerInfoMap = Collections.emptyMap();
    private ModuleInfo moduleInfo = null; // Can be null if Sheet3 is missing or invalid

    // --- Getters ---
    // Return unmodifiable views to prevent external modification

    /** @return An unmodifiable list of timestamp strings. */
    public List<String> getTimestamps() {
        return timestamps; // Already unmodifiable from setter or Collections.emptyList()
    }

    /** @return An unmodifiable list of headers from Sheet1. */
    public List<String> getSheet1Headers() {
        return sheet1Headers; // Already unmodifiable
    }

    /** @return An unmodifiable list of data rows (each row is a Map). */
    public List<Map<String, Double>> getSheet1Data() {
        return sheet1Data; // Already unmodifiable
    }

    /** @return An unmodifiable map of tracker names to TrackerInfo objects. */
    public Map<String, TrackerInfo> getTrackerInfoMap() {
        return trackerInfoMap; // Already unmodifiable
    }

    /** @return The ModuleInfo object, or null if not available. */
    public ModuleInfo getModuleInfo() {
        return moduleInfo;
    }

    // --- Setters ---
    // Use defensive copying to store copies of mutable input collections

    public void setTimestamps(List<String> timestamps) {
        // Store an immutable copy
        this.timestamps = (timestamps == null || timestamps.isEmpty())
                          ? Collections.emptyList()
                          : List.copyOf(timestamps); // List.copyOf creates unmodifiable list
    }

    public void setSheet1Headers(List<String> sheet1Headers) {
        this.sheet1Headers = (sheet1Headers == null || sheet1Headers.isEmpty())
                             ? Collections.emptyList()
                             : List.copyOf(sheet1Headers);
    }

    public void setSheet1Data(List<Map<String, Double>> sheet1Data) {
        if (sheet1Data == null || sheet1Data.isEmpty()) {
            this.sheet1Data = Collections.emptyList();
        } else {
            // Create a new list containing copies of the input maps
            List<Map<String, Double>> copiedList = new ArrayList<>(sheet1Data.size());
            for (Map<String, Double> rowMap : sheet1Data) {
                // Ensure rowMap is not null and create a copy
                copiedList.add(rowMap == null ? Collections.emptyMap() : Map.copyOf(rowMap));
            }
            // Make the outer list unmodifiable as well
            this.sheet1Data = Collections.unmodifiableList(copiedList);
        }
    }

    public void setTrackerInfoMap(Map<String, TrackerInfo> trackerInfoMap) {
        this.trackerInfoMap = (trackerInfoMap == null || trackerInfoMap.isEmpty())
                              ? Collections.emptyMap()
                              : Map.copyOf(trackerInfoMap); // Map.copyOf creates unmodifiable map
    }

    public void setModuleInfo(ModuleInfo moduleInfo) {
        // ModuleInfo is assumed immutable or state change doesn't matter here
        this.moduleInfo = moduleInfo;
    }

    // --- Convenience Methods ---

    /**
     * Checks if ModuleInfo was successfully loaded.
     * @return true if ModuleInfo is not null, false otherwise.
     */
    public boolean hasModuleInfo() {
        return moduleInfo != null;
    }

    /**
     * Retrieves the data row (map of header to value) for a specific timestamp.
     * Returns an empty map if the timestamp is not found.
     *
     * @param timestamp The timestamp string to look up.
     * @return A copy of the data map for the given timestamp, or an empty map if not found.
     */
    public Map<String, Double> getDataRowForTimestamp(String timestamp) {
        if (timestamp == null || timestamps.isEmpty() || sheet1Data.isEmpty()) {
            return Collections.emptyMap();
        }
        int index = timestamps.indexOf(timestamp);
        if (index != -1 && index < sheet1Data.size()) {
            // Return the map directly as it's already unmodifiable from setSheet1Data
            return sheet1Data.get(index);
            // If sheet1Data wasn't made of unmodifiable maps, return a copy:
            // return new HashMap<>(sheet1Data.get(index));
        }
        return Collections.emptyMap(); // Return empty map if timestamp not found
    }

    @Override
    public String toString() {
        return "ExcelData{" +
               "timestamps=" + (timestamps.size() > 5 ? timestamps.subList(0, 5) + "..." : timestamps) +
               ", headers=" + (sheet1Headers.size() > 5 ? sheet1Headers.subList(0, 5) + "..." : sheet1Headers) +
               ", dataRows=" + sheet1Data.size() +
               ", trackers=" + trackerInfoMap.size() +
               ", hasModuleInfo=" + hasModuleInfo() +
               '}';
    }
}