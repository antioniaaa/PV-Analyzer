package de.anton.pv.analyser.pv_analyzer.view;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Dialog window displaying outliers, including performance label.
 */
public class OutlierDialog extends JDialog {

    private final JTable outlierTable;
    private final OutlierTableModel tableModel;
    private final boolean moduleInfoAvailable;

    public OutlierDialog(Frame owner, boolean moduleInfoAvailable) {
        super(owner, "Zusammengefasste Ausreißer", false); // Slightly simpler title
        this.moduleInfoAvailable = moduleInfoAvailable;

        tableModel = new OutlierTableModel(moduleInfoAvailable);
        outlierTable = new JTable(tableModel);
        outlierTable.setAutoCreateRowSorter(true);
        outlierTable.setFillsViewportHeight(true);
        outlierTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        setupTableRenderersAndWidths();

        JScrollPane scrollPane = new JScrollPane(outlierTable); add(scrollPane, BorderLayout.CENTER);
        setSize(1350, 350); setMinimumSize(new Dimension(650, 200)); setLocationByPlatform(true);
    }

    private void setupTableRenderersAndWidths() {
        NumberRenderer numberRenderer = new NumberRenderer();
        CenterRenderer centerRenderer = new CenterRenderer();
        ClusterGroupRenderer clusterGroupRenderer = new ClusterGroupRenderer();
        TableColumnModel columnModel = outlierTable.getColumnModel();

        for (int i = 0; i < tableModel.getColumnCount(); i++) {
            String colName = tableModel.getColumnName(i); Class<?> colClass = tableModel.getColumnClass(i);
            if (colName.equals("Name")) { columnModel.getColumn(i).setPreferredWidth(180); columnModel.getColumn(i).setMinWidth(100); }
            else if (colName.equals("Performance")) { columnModel.getColumn(i).setPreferredWidth(90); columnModel.getColumn(i).setMaxWidth(110); columnModel.getColumn(i).setCellRenderer(centerRenderer); }
            else if (colName.equals("Anzahl Strings")) { columnModel.getColumn(i).setCellRenderer(centerRenderer); columnModel.getColumn(i).setPreferredWidth(80); columnModel.getColumn(i).setMaxWidth(100); }
            else if (colName.equals("Cluster Gruppe")) { columnModel.getColumn(i).setCellRenderer(clusterGroupRenderer); columnModel.getColumn(i).setPreferredWidth(90); columnModel.getColumn(i).setMaxWidth(120); }
            else if (Number.class.isAssignableFrom(colClass)) { columnModel.getColumn(i).setCellRenderer(numberRenderer); if (colName.contains("Leistung") || colName.contains("Spannung")) { columnModel.getColumn(i).setPreferredWidth(110); } else if (colName.contains("Strom") || colName.contains("Ohm") || colName.contains("Spez.")) { columnModel.getColumn(i).setPreferredWidth(100); } else { columnModel.getColumn(i).setPreferredWidth(120); } columnModel.getColumn(i).setMinWidth(70); }
            else { columnModel.getColumn(i).setPreferredWidth(100); }
        }
    }

    public void updateData(List<CalculatedDataPoint> outliers) { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> updateData(outliers)); return; } tableModel.setData(outliers); setTitle("Ausreißer (" + (outliers != null ? outliers.size() : 0) + ")"); }
    public boolean isModuleInfoAvailable() { return moduleInfoAvailable; }

    private static class OutlierTableModel extends AbstractTableModel {
         private List<CalculatedDataPoint> outlierData = new ArrayList<>(); private final boolean moduleInfoAvailable;
         private static final List<String> COLUMN_NAMES_BASE = List.of( "Name", "DC-Leistung (kW)", "Spez. Leistung", "Performance", "DC-Spannung (V)", "Nennleistung (kWp)", "Strom/String (A)", "Ohm (Ω)", "Anzahl Strings" );
         private static final List<String> COLUMN_NAMES_MODULE = List.of( "Module/String", "Diff Spannung (Modul-Vmpp)", "Diff Strom (Modul-Impp)", "Diff Leistung (Modul-Pmpp)" );
         private static final List<String> COLUMN_NAMES_END = List.of( "Cluster Gruppe" ); private final List<String> columnNamesCombined;
        public OutlierTableModel(boolean moduleInfoAvailable) { this.moduleInfoAvailable = moduleInfoAvailable; List<String> combined = new ArrayList<>(COLUMN_NAMES_BASE); if (moduleInfoAvailable) { combined.addAll(COLUMN_NAMES_MODULE); } combined.addAll(COLUMN_NAMES_END); this.columnNamesCombined = Collections.unmodifiableList(combined); }
        public void setData(List<CalculatedDataPoint> outliers) { this.outlierData = (outliers == null) ? new ArrayList<>() : outliers.stream().filter(Objects::nonNull).sorted(Comparator.comparing(CalculatedDataPoint::getName, String.CASE_INSENSITIVE_ORDER)).collect(Collectors.toList()); fireTableDataChanged(); }
        @Override public int getRowCount() { return outlierData.size(); } @Override public int getColumnCount() { return columnNamesCombined.size(); } @Override public String getColumnName(int column) { return columnNamesCombined.get(column); }
        @Override public Class<?> getColumnClass(int columnIndex) { String colName = getColumnName(columnIndex); if (colName.equals("Name") || colName.equals("Performance")) return String.class; if (colName.equals("Anzahl Strings") || colName.equals("Cluster Gruppe")) return Integer.class; return Double.class; }
        @Override public Object getValueAt(int rowIndex, int columnIndex) { if (rowIndex < 0 || rowIndex >= outlierData.size()) { return null; } CalculatedDataPoint point = outlierData.get(rowIndex); if (point == null) { return null; } String colName = getColumnName(columnIndex); try { switch (colName) { case "Name": return point.getName(); case "DC-Leistung (kW)": return formatDouble(point.getDcLeistungKW()); case "Spez. Leistung": return formatDouble(point.getSpezifischeLeistung()); case "Performance": return point.getPerformanceLabel(); case "DC-Spannung (V)": return formatDouble(point.getDcSpannungV()); case "Nennleistung (kWp)": return formatDouble(point.getNennleistungKWp()); case "Strom/String (A)": return formatDouble(point.getStromJeStringA()); case "Ohm (Ω)": return formatDouble(point.getOhm()); case "Anzahl Strings": return point.getAnzahlStrings(); case "Module/String": return moduleInfoAvailable ? formatDouble(point.getAnzahlModuleJeString()) : null; case "Diff Spannung (Modul-Vmpp)": return moduleInfoAvailable ? formatDouble(point.getDiffSpannungModulVmpp()) : null; case "Diff Strom (Modul-Impp)": return moduleInfoAvailable ? formatDouble(point.getDiffStromModulImpp()) : null; case "Diff Leistung (Modul-Pmpp)": return moduleInfoAvailable ? formatDouble(point.getDiffLeistungModulPmpp()) : null; case "Cluster Gruppe": return point.getClusterGroup(); default: System.err.println("Warning: Unhandled column name in OutlierTableModel: " + colName); return null; } } catch (Exception e) { System.err.println("Error getting value for outlier row " + rowIndex + ", col " + columnIndex + " (" + colName + "): " + e.getMessage()); return null; } }
        private Double formatDouble(double value) { return Double.isNaN(value) ? null : value; }
    }
     private static class NumberRenderer extends DefaultTableCellRenderer { private static final DecimalFormat FG = new DecimalFormat("#,##0.###"); private static final DecimalFormat FP = new DecimalFormat("#,##0.000"); private static final DecimalFormat FV = new DecimalFormat("#,##0.0"); private static final DecimalFormat FI = new DecimalFormat("#,##0.00"); private static final DecimalFormat FO = new DecimalFormat("#,##0.0"); private static final DecimalFormat FS = new DecimalFormat("0.000"); public NumberRenderer() { setHorizontalAlignment(SwingConstants.RIGHT); } @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) { Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column); if (value instanceof Number) { String colName = table.getColumnName(column); DecimalFormat formatter; if (colName.contains("Leistung (kW)") || colName.contains("Leistung (Modul-Pmpp)")) formatter = FP; else if (colName.contains("Spannung")) formatter = FV; else if (colName.contains("Strom")) formatter = FI; else if (colName.contains("Ohm")) formatter = FO; else if (colName.contains("Spez.")) formatter = FS; else formatter = FG; setText(formatter.format(value)); } else if (value == null) { setText(""); } else { setText(value.toString()); } return c; } }
     private static class CenterRenderer extends DefaultTableCellRenderer { public CenterRenderer() { setHorizontalAlignment(SwingConstants.CENTER); } }
     private static class ClusterGroupRenderer extends DefaultTableCellRenderer { private static final Color NC = Color.GRAY; public ClusterGroupRenderer() { setHorizontalAlignment(SwingConstants.CENTER); } @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) { Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column); if (value instanceof Integer) { int group = (Integer) value; if (group == MyOPTICS.NOISE) { setText("Noise"); c.setForeground(isSelected ? table.getSelectionForeground() : NC); } else { setText(String.valueOf(group)); c.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground()); } } else if (value == null) { setText(""); } else { setText(value.toString()); } return c; } }
}