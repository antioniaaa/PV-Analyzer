package de.anton.pv.analyser.pv_analyzer.model;

import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Utility class to export analysis data to an Excel file.
 */
public class ExcelExporter {

    private static final Logger logger = LoggerFactory.getLogger(ExcelExporter.class);

    // Define column headers including performance label
    private static final List<String> COLUMN_NAMES_STRUCTURE = List.of("Ausrichtung", "Zeitstempel (Quelle)");
    private static final List<String> COLUMN_NAMES_BASE = List.of( "Name", "DC-Leistung (kW)", "Spez. Leistung (kW/kWp)", "Performance", "DC-Spannung (V)", "Nennleistung (kWp)", "Strom/String (A)", "Ohm (Ω)", "Anzahl Strings");
    private static final List<String> COLUMN_NAMES_MODULE = List.of( "Module/String", "Diff Spannung (Modul-Vmpp)", "Diff Strom (Modul-Impp)", "Diff Leistung (Modul-Pmpp)");
    private static final List<String> COLUMN_NAMES_END = List.of( "Cluster Gruppe", "Ausreißer?");
    private List<String> columnNamesCombined; // Build dynamically

    /**
     * Exports the provided list of CalculatedDataPoint objects to an Excel file (.xlsx).
     * Data is sorted by orientation, source timestamp, then name.
     */
    public void exportData(List<CalculatedDataPoint> dataPoints, String filePath, boolean moduleInfoAvailable)
            throws IOException, InterruptedException {

        if (dataPoints == null || dataPoints.isEmpty()) { logger.warn("No data provided for Excel export to {}", filePath); return; }
        if (filePath == null || filePath.trim().isEmpty()) { throw new IllegalArgumentException("Output file path cannot be null or empty."); }

        // Sort data
        logger.debug("Sorting {} data points for export...", dataPoints.size());
        Comparator<CalculatedDataPoint> comparator = Comparator .<CalculatedDataPoint, String>comparing(CalculatedDataPoint::getAusrichtung, String.CASE_INSENSITIVE_ORDER) .thenComparing(CalculatedDataPoint::getSourceTimestamp, Comparator.nullsLast(String::compareTo)) .thenComparing(CalculatedDataPoint::getName, String.CASE_INSENSITIVE_ORDER);
        List<CalculatedDataPoint> sortedData = dataPoints.stream().filter(Objects::nonNull).sorted(comparator).collect(Collectors.toList());
        if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("Excel export cancelled after sorting."); }

        // Build combined column header list
        columnNamesCombined = new ArrayList<>(COLUMN_NAMES_STRUCTURE); columnNamesCombined.addAll(COLUMN_NAMES_BASE); if (moduleInfoAvailable) { columnNamesCombined.addAll(COLUMN_NAMES_MODULE); } columnNamesCombined.addAll(COLUMN_NAMES_END);

        logger.info("Starting Excel export process to: {}", filePath);
        try (Workbook workbook = new XSSFWorkbook(); FileOutputStream fileOut = new FileOutputStream(filePath)) {
            Sheet sheet = workbook.createSheet("AnalyseDaten"); Font headerFont = workbook.createFont(); headerFont.setBold(true); CellStyle headerStyle = workbook.createCellStyle(); headerStyle.setFont(headerFont);
            Row headerRow = sheet.createRow(0); for (int i = 0; i < columnNamesCombined.size(); i++) { Cell cell = headerRow.createCell(i); cell.setCellValue(columnNamesCombined.get(i)); cell.setCellStyle(headerStyle); }

            int rowNum = 1;
            for (CalculatedDataPoint point : sortedData) {
                 if (Thread.currentThread().isInterrupted()) { throw new InterruptedException("Excel export cancelled during data writing."); }
                Row row = sheet.createRow(rowNum++); int cellNum = 0;
                row.createCell(cellNum++).setCellValue(point.getAusrichtung()); row.createCell(cellNum++).setCellValue(point.getSourceTimestamp()); row.createCell(cellNum++).setCellValue(point.getName());
                createNumericCell(row, cellNum++, point.getDcLeistungKW()); createNumericCell(row, cellNum++, point.getSpezifischeLeistung());
                row.createCell(cellNum++).setCellValue(point.getPerformanceLabel()); // Add Performance
                createNumericCell(row, cellNum++, point.getDcSpannungV()); createNumericCell(row, cellNum++, point.getNennleistungKWp()); createNumericCell(row, cellNum++, point.getStromJeStringA()); createNumericCell(row, cellNum++, point.getOhm()); row.createCell(cellNum++).setCellValue(point.getAnzahlStrings());
                if (moduleInfoAvailable) { createNumericCell(row, cellNum++, point.getAnzahlModuleJeString()); createNumericCell(row, cellNum++, point.getDiffSpannungModulVmpp()); createNumericCell(row, cellNum++, point.getDiffStromModulImpp()); createNumericCell(row, cellNum++, point.getDiffLeistungModulPmpp()); }
                Cell clusterCell = row.createCell(cellNum++); int clusterGroup = point.getClusterGroup(); if (clusterGroup == MyOPTICS.NOISE) { clusterCell.setCellValue("Noise"); } else { clusterCell.setCellValue(clusterGroup); }
                row.createCell(cellNum++).setCellValue(point.isOutlier() ? "Ja" : "Nein");
            }

            logger.debug("Autosizing columns..."); for (int i = 0; i < columnNamesCombined.size(); i++) { sheet.autoSizeColumn(i); }
            logger.debug("Writing workbook to file..."); workbook.write(fileOut); logger.info("Excel export completed successfully to: {}", filePath);
        } catch (IOException e) { logger.error("IOException during Excel export to {}", filePath, e); throw e; }
        catch (Exception e) { logger.error("Unexpected error during Excel export to {}", filePath, e); throw new IOException("Unerwarteter Fehler beim Excel-Export: " + e.getMessage(), e); }
    }

    private void createNumericCell(Row row, int colIndex, double value) { if (!Double.isNaN(value) && !Double.isInfinite(value)) { row.createCell(colIndex).setCellValue(value); } else { row.createCell(colIndex, CellType.BLANK); } }
}