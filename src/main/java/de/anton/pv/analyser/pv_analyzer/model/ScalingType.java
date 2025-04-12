package de.anton.pv.analyser.pv_analyzer.model; // Assuming model package

/**
 * Enumeration defining the available data scaling methods.
 * Includes a display name for use in UI elements (e.g., ComboBoxes).
 */
public enum ScalingType {
    NONE("Keine"),          // No scaling applied
    MIN_MAX("Min-Max"),     // Scale features to range [0, 1]
    Z_SCORE("Z-Score");     // Standardize features to mean 0, stddev 1

    private final String displayName;

    /**
     * Constructor for the enum constants.
     * @param displayName The user-friendly name for display purposes.
     */
    ScalingType(String displayName) {
        this.displayName = displayName;
    }

    /**
     * Returns the user-friendly display name for the scaling type.
     * This is intended for use in UI components like JComboBox.
     * @return The display name string.
     */
    @Override
    public String toString() {
        return displayName;
    }

    /**
     * Finds a ScalingType enum constant based on its display name (case-insensitive).
     * Useful for converting user selection back to the enum type.
     *
     * @param displayName The display name to search for.
     * @return The corresponding ScalingType, or null if no match is found.
     */
    public static ScalingType fromDisplayName(String displayName) {
        if (displayName == null) {
            return null;
        }
        for (ScalingType type : ScalingType.values()) {
            if (type.displayName.equalsIgnoreCase(displayName.trim())) {
                return type;
            }
        }
        return null; // Or throw an exception if name must be valid
    }
}