package de.anton.pv.analyser.pv_analyzer.model;

import java.util.Objects;

/**
 * Represents static information about a specific PV tracker, typically read from Sheet2.
 * This class is intended to be immutable.
 */
public final class TrackerInfo { // Make class final

    private final String name;             // Unique tracker identifier
    private final double nennleistungkWp;  // Nominal power in kWp
    private final String ausrichtung;      // Orientation (e.g., "Süd", "Ost/West")
    private final int anzahlStrings;     // Number of strings connected

    /**
     * Constructor for TrackerInfo.
     *
     * @param name            Unique tracker name (must not be null or empty).
     * @param nennleistungkWp Nominal power in kWp (must be non-negative).
     * @param ausrichtung     Orientation string (must not be null or empty).
     * @param anzahlStrings   Number of strings (must be positive).
     */
    public TrackerInfo(String name, double nennleistungkWp, String ausrichtung, int anzahlStrings) {
        // Use Objects.requireNonNull and checks for better validation
        this.name = Objects.requireNonNull(name, "Tracker name cannot be null.");
        if (name.trim().isEmpty()) {
            throw new IllegalArgumentException("Tracker name cannot be empty.");
        }

        if (nennleistungkWp < 0) {
            throw new IllegalArgumentException("Nennleistung (kWp) cannot be negative for tracker '" + name + "'. Got: " + nennleistungkWp);
        }
        this.nennleistungkWp = nennleistungkWp;

        this.ausrichtung = Objects.requireNonNull(ausrichtung, "Ausrichtung cannot be null for tracker '" + name + "'.");
        if (ausrichtung.trim().isEmpty()) {
             throw new IllegalArgumentException("Ausrichtung cannot be empty for tracker '" + name + "'.");
        }

        if (anzahlStrings <= 0) {
            throw new IllegalArgumentException("Anzahl Strings must be positive for tracker '" + name + "'. Got: " + anzahlStrings);
        }
        this.anzahlStrings = anzahlStrings;
    }

    // --- Getters ---
    public String getName() { return name; }
    public double getNennleistungkWp() { return nennleistungkWp; }
    public String getAusrichtung() { return ausrichtung; }
    public int getAnzahlStrings() { return anzahlStrings; }

    @Override
    public String toString() {
        // Use String.format for consistent output
        return String.format(
            "TrackerInfo[Name='%s', Nennleistung=%.2f kWp, Ausrichtung='%s', Strings=%d]",
            name, nennleistungkWp, ausrichtung, anzahlStrings);
    }

    // --- equals() and hashCode() ---
    // Implement based on the unique identifier field 'name' if needed for Maps/Sets.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrackerInfo that = (TrackerInfo) o;
        return Objects.equals(name, that.name); // Equality based on name
    }

    @Override
    public int hashCode() {
        return Objects.hash(name); // HashCode based on name
    }
}