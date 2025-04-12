package de.anton.pv.analyser.pv_analyzer.model;

import java.util.Objects; // For potential future equals/hashCode

/**
 * Represents static information about a PV module type, typically read from Sheet3.
 * This class is intended to be immutable.
 */
public final class ModuleInfo { // Make class final if no subclasses are expected

    private final double pnennKWp; // Nominal power (e.g., from datasheet, STC) in kWp
    private final double pmppKW;   // Power at Maximum Power Point (MPP) in kW
    private final double vmppV;    // Voltage at MPP in Volts
    private final double imppA;    // Current at MPP in Amperes

    /**
     * Constructor for ModuleInfo.
     *
     * @param pnennKWp Nominal power in kWp (must be non-negative).
     * @param pmppKW   Power at MPP in kW (must be non-negative).
     * @param vmppV    Voltage at MPP in Volts (must be non-negative).
     * @param imppA    Current at MPP in Amperes (must be non-negative).
     */
    public ModuleInfo(double pnennKWp, double pmppKW, double vmppV, double imppA) {
        // Add validation for non-negative values if desired
        if (pnennKWp < 0 || pmppKW < 0 || vmppV < 0 || imppA < 0) {
            // Log or throw? Let's throw for critical invalid data.
            throw new IllegalArgumentException(String.format(
                "Module parameters cannot be negative: Pnenn=%.3f, Pmpp=%.3f, Vmpp=%.1f, Impp=%.2f",
                pnennKWp, pmppKW, vmppV, imppA));
        }
        this.pnennKWp = pnennKWp;
        this.pmppKW = pmppKW;
        this.vmppV = vmppV;
        this.imppA = imppA;
    }

    // --- Getters ---
    public double getPnennKWp() { return pnennKWp; }
    public double getPmppKW() { return pmppKW; }
    public double getVmppV() { return vmppV; }
    public double getImppA() { return imppA; }

     @Override
    public String toString() {
        // Use String.format for better control over number formatting in toString
        return String.format(
            "ModuleInfo[Pnenn=%.3f kWp, Pmpp=%.3f kW, Vmpp=%.1f V, Impp=%.2f A]",
            pnennKWp, pmppKW, vmppV, imppA);
    }

    // --- equals() and hashCode() ---
    // Implement if ModuleInfo objects need to be compared by value or used in HashSets/Maps.
    // Based on all fields since it's immutable.
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ModuleInfo that = (ModuleInfo) o;
        return Double.compare(that.pnennKWp, pnennKWp) == 0 &&
               Double.compare(that.pmppKW, pmppKW) == 0 &&
               Double.compare(that.vmppV, vmppV) == 0 &&
               Double.compare(that.imppA, imppA) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(pnennKWp, pmppKW, vmppV, imppA);
    }
}