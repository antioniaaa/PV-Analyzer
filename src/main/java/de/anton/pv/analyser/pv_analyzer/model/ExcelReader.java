package de.anton.pv.analyser.pv_analyzer.model;

import org.apache.poi.ss.usermodel.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Reads data from a specifically formatted Excel file containing PV plant data.
 * Expects sheets named "Sheet1", "Sheet2", and optionally "Sheet3".
 * - Sheet1: Time series data (Timestamp, TrackerA/Metric1, TrackerA/Metric2, ...)
 * - Sheet2: Static tracker information (Name, Nennleistung, Ausrichtung, Anzahl Strings)
 * - Sheet3: Optional static module information (Pnenn, Pmpp, Vmpp, Impp)
 */
public class ExcelReader {

    private static final Logger logger = LoggerFactory.getLogger(ExcelReader.class);
    // Define expected sheet names as constants
    private static final String SHEET1_NAME = "Tabelle1";
    private static final String SHEET2_NAME = "Tabelle2";
    private static final String SHEET3_NAME = "Tabelle3"; // Optional sheet
    // Consistent date format for parsing and potentially storing timestamps
    private static final DateFormat DATE_FORMAT = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    // Define expected headers in Sheet2
    private static final String HEADER_TRACKER_NAME = "Name";
    private static final String HEADER_TRACKER_POWER = "Nennleistung";
    private static final String HEADER_TRACKER_ORIENTATION = "Ausrichtung";
    private static final String HEADER_TRACKER_STRINGS = "Anzahl Strings";


    /**
     * Reads the Excel file and populates an ExcelData object.
     *
     * @param file The Excel file to read.
     * @return An ExcelData object containing the parsed data.
     * @throws IOException If the file cannot be read, required sheets are missing,
     *                     or essential data cannot be parsed correctly.
     */
    public ExcelData readExcel(File file) throws IOException {
        Objects.requireNonNull(file, "Input file cannot be null.");
        logger.info("Starting to read Excel file: {}", file.getAbsolutePath());

        ExcelData excelData = new ExcelData();

        // Use try-with-resources for reliable closing of InputStream and Workbook
        try (InputStream fis = new FileInputStream(file);
             Workbook workbook = WorkbookFactory.create(fis)) {

            // --- Read Sheet2: Tracker Info (Mandatory) ---
            Sheet sheet2 = workbook.getSheet(SHEET2_NAME);
            if (sheet2 == null) {
                throw new IOException("Required sheet '" + SHEET2_NAME + "' not found in the Excel file.");
            }
            Map<String, TrackerInfo> trackerMap = readTrackerInfo(sheet2);
            if (trackerMap.isEmpty()) {
                // If readTrackerInfo logs errors, this might indicate a format issue
                throw new IOException("No valid tracker information found or parsed in '" + SHEET2_NAME + "'. Check sheet format and headers.");
            }
            excelData.setTrackerInfoMap(trackerMap);
            logger.info("Read {} tracker info entries from '{}'.", trackerMap.size(), SHEET2_NAME);

            // --- Read Sheet1: Time Series Data (Mandatory) ---
            Sheet sheet1 = workbook.getSheet(SHEET1_NAME);
            if (sheet1 == null) {
                throw new IOException("Required sheet '" + SHEET1_NAME + "' not found in the Excel file.");
            }
            // This method populates timestamps, headers, and dataRows within excelData
            readTimeSeriesData(sheet1, excelData);
            if (excelData.getTimestamps().isEmpty()) {
                // If no timestamps were read, the sheet might be empty or malformed
                throw new IOException("No valid timestamps or data rows found or parsed in '" + SHEET1_NAME + "'. Check sheet format.");
            }
            logger.info("Read {} timestamps and data rows from '{}'.", excelData.getTimestamps().size(), SHEET1_NAME);

            // --- Read Sheet3: Module Info (Optional) ---
            Sheet sheet3 = workbook.getSheet(SHEET3_NAME);
            if (sheet3 != null) {
                try {
                    ModuleInfo moduleInfo = readModuleInfo(sheet3);
                    excelData.setModuleInfo(moduleInfo); // Will be null if parsing failed
                    if (moduleInfo != null) {
                        logger.info("Successfully read module info from '{}': {}", SHEET3_NAME, moduleInfo);
                    } else {
                        logger.warn("Optional sheet '{}' found, but could not parse valid module info. Proceeding without module data.", SHEET3_NAME);
                    }
                } catch (Exception e) {
                    // Catch errors specifically during Sheet3 processing
                    logger.warn("Error reading optional sheet '{}'. Proceeding without module data. Error: {}", SHEET3_NAME, e.getMessage(), e);
                    excelData.setModuleInfo(null); // Ensure it's null on error
                }
            } else {
                logger.info("Optional sheet '{}' not found. Proceeding without module data.", SHEET3_NAME);
                excelData.setModuleInfo(null); // Explicitly set to null
            }

        } catch (IOException ioe) {
             // Catch IO errors (file not found, read errors) and re-throw
             logger.error("IO error reading Excel file: {}", file.getAbsolutePath(), ioe);
             throw ioe;
        } catch (Exception e) {
            // Catch other potential errors during workbook processing (e.g., invalid format)
            logger.error("Error processing Excel file: {}", file.getAbsolutePath(), e);
            throw new IOException("Error processing Excel file: " + e.getMessage(), e);
        }

        logger.info("Finished reading Excel file: {}", file.getAbsolutePath());
        return excelData;
    }

    /** Reads static tracker information from Sheet2. */
    private Map<String, TrackerInfo> readTrackerInfo(Sheet sheet) {
        Map<String, TrackerInfo> trackerMap = new LinkedHashMap<>(); // Preserve insertion order if needed
        DataFormatter formatter = new DataFormatter(); // Handles different cell types consistently
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

        // --- Find Header Columns ---
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            logger.error("Sheet '{}' is missing the header row (Row 1). Cannot read tracker info.", sheet.getSheetName());
            return Collections.emptyMap();
        }

        int nameCol = -1, powerCol = -1, orientationCol = -1, stringsCol = -1;
        for (Cell cell : headerRow) {
             if (cell == null) continue;
            String headerText = getCellValueAsString(cell, formatter, evaluator).trim();
            int currentCellIndex = cell.getColumnIndex();

            if (HEADER_TRACKER_NAME.equalsIgnoreCase(headerText)) nameCol = currentCellIndex;
            else if (HEADER_TRACKER_POWER.equalsIgnoreCase(headerText)) powerCol = currentCellIndex;
            else if (HEADER_TRACKER_ORIENTATION.equalsIgnoreCase(headerText)) orientationCol = currentCellIndex;
            else if (HEADER_TRACKER_STRINGS.equalsIgnoreCase(headerText)) stringsCol = currentCellIndex;
        }

        // Validate that all required columns were found
        if (nameCol == -1 || powerCol == -1 || orientationCol == -1 || stringsCol == -1) {
            logger.error("Could not find all required columns in '{}' header. Missing: {}{}{}{}. Check spelling and presence.",
                         sheet.getSheetName(),
                         (nameCol == -1 ? "'"+HEADER_TRACKER_NAME+"' " : ""),
                         (powerCol == -1 ? "'"+HEADER_TRACKER_POWER+"' " : ""),
                         (orientationCol == -1 ? "'"+HEADER_TRACKER_ORIENTATION+"' " : ""),
                         (stringsCol == -1 ? "'"+HEADER_TRACKER_STRINGS+"' " : ""));
            return Collections.emptyMap();
        }
        logger.debug("Found required columns in '{}': Name={}, Power={}, Orientation={}, Strings={}",
                     sheet.getSheetName(), nameCol, powerCol, orientationCol, stringsCol);


        // --- Read Data Rows ---
        // Iterate from the second row (index 1) to the last row number
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
             Row row = sheet.getRow(i);
             if (row == null || row.getCell(nameCol) == null || getCellValueAsString(row.getCell(nameCol), formatter, evaluator).trim().isEmpty()) {
                 logger.trace("Skipping empty or invalid row in '{}' at index {}", sheet.getSheetName(), i);
                 continue; // Skip empty rows or rows without a name
             }

            try {
                String name = getCellValueAsString(row.getCell(nameCol), formatter, evaluator).trim();
                // Nennleistung: Parse as double, potentially removing units like "kWp"
                double nennleistung = parseDoubleWithOptionalUnit(getCellValueAsString(row.getCell(powerCol), formatter, evaluator), "kwp");
                // Ausrichtung: Read as string
                String ausrichtung = getCellValueAsString(row.getCell(orientationCol), formatter, evaluator).trim();
                // Anzahl Strings: Parse as double first for flexibility, then round to int
                double anzahlStringsDouble = getCellValueAsDouble(row.getCell(stringsCol), formatter, evaluator);
                int anzahlStrings = (!Double.isNaN(anzahlStringsDouble) && anzahlStringsDouble > 0)
                                    ? (int) Math.round(anzahlStringsDouble)
                                    : 0; // Default to 0 if invalid or non-positive

                // Validate parsed values
                if (!Double.isNaN(nennleistung) && nennleistung >= 0 // Power should be non-negative
                    && ausrichtung != null && !ausrichtung.isEmpty()
                    && anzahlStrings > 0) { // Must have at least one string

                    TrackerInfo info = new TrackerInfo(name, nennleistung, ausrichtung, anzahlStrings);
                    if (trackerMap.containsKey(name)) {
                         logger.warn("Duplicate tracker name '{}' found in '{}' at row {}. Overwriting previous entry.", name, sheet.getSheetName(), i + 1);
                    }
                    trackerMap.put(name, info);
                } else {
                    // Log skipped rows with details for debugging
                    logger.warn("Skipping invalid tracker row in '{}' for tracker '{}' (Row {}): Nennleistung={}, Ausrichtung='{}', Strings={} (Parsed Int: {})",
                            sheet.getSheetName(), name, i + 1,
                            getCellValueAsString(row.getCell(powerCol), formatter, evaluator), // Log raw value
                            ausrichtung,
                            getCellValueAsString(row.getCell(stringsCol), formatter, evaluator), // Log raw value
                            anzahlStrings);
                }
            } catch (Exception e) {
                // Catch errors during row parsing
                logger.warn("Error parsing row {} in '{}': {}. Skipping row.",
                            i + 1, sheet.getSheetName(), e.getMessage(), e);
            }
        }
        return trackerMap;
    }

    /** Reads time series data from Sheet1. */
    private void readTimeSeriesData(Sheet sheet, ExcelData excelData) {
        List<String> headers = new ArrayList<>();
        List<String> timestamps = new ArrayList<>();
        List<Map<String, Double>> dataRows = new ArrayList<>();
        DataFormatter formatter = new DataFormatter(); // Handles cell types
        FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator(); // For formulas

        // --- Read Header Row ---
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new RuntimeException("Sheet '" + sheet.getSheetName() + "' is missing the header row (Row 1).");
        }
        for (Cell cell : headerRow) {
             if (cell != null) {
                 headers.add(getCellValueAsString(cell, formatter, evaluator).trim());
             } else {
                 headers.add(""); // Add empty string for potential blank header cells
             }
        }
        // Validate first header is related to date/time
        if (headers.isEmpty() || !headers.get(0).toLowerCase().contains("date") && !headers.get(0).toLowerCase().contains("zeit")) {
             throw new RuntimeException("Sheet '" + sheet.getSheetName() + "' header format error. First column header should be related to 'Date' or 'Zeit'. Found: '" + (headers.isEmpty() ? "None" : headers.get(0)) + "'");
        }
        excelData.setSheet1Headers(headers);
        logger.debug("Read {} headers from '{}': {}", headers.size(), sheet.getSheetName(), headers);

        // --- Read Data Rows ---
        // Iterate from second row (index 1) to last physical row number
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row == null) {
                 logger.trace("Skipping null row in '{}' at index {}", sheet.getSheetName(), i);
                 continue; // Skip fully empty rows
            }

            Map<String, Double> dataRowMap = new HashMap<>();
            String currentTimestamp = null;
            Cell firstCell = row.getCell(0); // Timestamp cell should be the first one

            // --- Read Timestamp (Column 0) ---
             if (firstCell != null && firstCell.getCellType() != CellType.BLANK) {
                 try {
                     // Try reading as Date cell first
                     if (firstCell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(firstCell)) {
                         Date dateValue = firstCell.getDateCellValue();
                         if (dateValue != null) {
                            currentTimestamp = DATE_FORMAT.format(dateValue);
                         } else {
                             logger.warn("Date-formatted cell {} in '{}' returned null date value.", firstCell.getAddress(), sheet.getSheetName());
                         }
                     } else {
                         // If not date formatted, try reading as string and parsing
                         String tsString = getCellValueAsString(firstCell, formatter, evaluator).trim();
                         if (!tsString.isEmpty()) {
                             try {
                                 // Attempt parsing with the defined format
                                 Date parsedDate = DATE_FORMAT.parse(tsString);
                                 currentTimestamp = DATE_FORMAT.format(parsedDate); // Reformat for consistency
                             } catch (ParseException pe) {
                                 // If parsing fails, use the string value as is, but log a warning
                                 logger.warn("Could not parse timestamp string '{}' in '{}', Row {}. Using raw value.", tsString, sheet.getSheetName(), i + 1);
                                 currentTimestamp = tsString; // Fallback to using the raw string
                             }
                         }
                     }
                 } catch (Exception e) {
                    // Catch any other errors during timestamp reading/parsing
                    logger.warn("Error reading timestamp in '{}', Row {}: {}. Skipping timestamp for this row.",
                                sheet.getSheetName(), i + 1, e.getMessage());
                    // Decide whether to skip the whole row or just the timestamp
                    // Skipping the row if timestamp is crucial:
                    // continue;
                    // Or use a placeholder:
                     currentTimestamp = "Invalid Timestamp @ Row " + (i+1);
                 }
             }

            // If timestamp is still null or empty after trying, skip the row
             if (currentTimestamp == null || currentTimestamp.trim().isEmpty()) {
                 logger.warn("Missing or invalid timestamp in '{}', Row {}. Skipping row.", sheet.getSheetName(), i + 1);
                 continue;
             }
             timestamps.add(currentTimestamp);

            // --- Read Data Values (Columns 1 to N) ---
             // Use header count for loop bounds, but check cell index safety
             for (int j = 1; j < headers.size(); j++) {
                 Cell cell = row.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL); // Get cell or null if missing
                 String header = headers.get(j);
                 if (header == null || header.trim().isEmpty()) {
                      logger.trace("Skipping data column with empty header at index {} in '{}'", j, sheet.getSheetName());
                      continue; // Skip columns with no header
                 }

                 // Parse cell value as double (handles numbers, strings, formulas, blanks)
                 double value = getCellValueAsDouble(cell, formatter, evaluator);
                 // Store the value (even if NaN) in the map for this row
                 dataRowMap.put(header, value);
            }

            // Add the completed row map to the list of data rows
             dataRows.add(dataRowMap);
        } // End of row loop

        // Set the collected data into the ExcelData object
        excelData.setTimestamps(timestamps);
        excelData.setSheet1Data(dataRows);
    }

     /** Reads optional module information from Sheet3. */
     private ModuleInfo readModuleInfo(Sheet sheet) {
         DataFormatter formatter = new DataFormatter(); // Handles cell types
         FormulaEvaluator evaluator = sheet.getWorkbook().getCreationHelper().createFormulaEvaluator();

         // Variables to store parsed values, initialized to NaN
         double pnennKWp = Double.NaN;
         double pmppKW = Double.NaN;
         double vmppV = Double.NaN;
         double imppA = Double.NaN;

         // Map expected labels (lowercase) to where we store the value
         Map<String, java.util.function.Consumer<Double>> valueConsumers = new HashMap<>();
         // Use temporary array to bypass lambda's final variable requirement
         final double[] values = {pnennKWp, pmppKW, vmppV, imppA};
         valueConsumers.put("pnenn", v -> values[0] = v);
         valueConsumers.put("pmpp", v -> values[1] = v);
         valueConsumers.put("vmpp", v -> values[2] = v);
         valueConsumers.put("impp", v -> values[3] = v);
         // Add variations if needed, e.g., "p nenn", "p_nenn"
         valueConsumers.put("pnenn:", v -> values[0] = v);
         valueConsumers.put("pmpp:", v -> values[1] = v);
         valueConsumers.put("vmpp:", v -> values[2] = v);
         valueConsumers.put("impp:", v -> values[3] = v);


         // Flexible reading: Iterate through rows, look for label/value pairs
         logger.debug("Scanning '{}' for module info labels: {}", sheet.getSheetName(), valueConsumers.keySet());
         for (Row row : sheet) {
             if (row == null) continue;
             // Check adjacent cells for label-value pattern
             for (int i = 0; i < row.getLastCellNum() - 1; i++) {
                 Cell labelCell = row.getCell(i, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                 Cell valueCell = row.getCell(i + 1, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);

                 if (labelCell != null && valueCell != null) {
                     String labelText = getCellValueAsString(labelCell, formatter, evaluator).toLowerCase().trim();

                     // Check if this label is one we're looking for
                     if (valueConsumers.containsKey(labelText)) {
                         // Attempt to parse the value from the next cell
                         double value = getCellValueAsDouble(valueCell, formatter, evaluator);
                         if (!Double.isNaN(value)) {
                             logger.trace("Found module info in '{}': Label='{}', Value={}", sheet.getSheetName(), labelText, value);
                             valueConsumers.get(labelText).accept(value); // Store the parsed value
                         } else {
                             logger.warn("Found label '{}' in '{}' but could not parse numeric value from adjacent cell {}. Raw: '{}'",
                                         labelText, sheet.getSheetName(), valueCell.getAddress(), getCellValueAsString(valueCell, formatter, evaluator));
                         }
                     }
                 }
             }
         }

         // Assign parsed values back from the temporary array
         pnennKWp = values[0];
         pmppKW = values[1];
         vmppV = values[2];
         imppA = values[3];

         // Validate if all required values were found and are valid (non-NaN)
         if (!Double.isNaN(pnennKWp) && pnennKWp >= 0 &&
             !Double.isNaN(pmppKW) && pmppKW >= 0 &&
             !Double.isNaN(vmppV) && vmppV >= 0 &&
             !Double.isNaN(imppA) && imppA >= 0) {
            // All required values found and seem plausible (non-negative)
            return new ModuleInfo(pnennKWp, pmppKW, vmppV, imppA);
        } else {
             // Log which values were missing or invalid
             logger.warn("Could not read all required module info from '{}'. Found: Pnenn={}, Pmpp={}, Vmpp={}, Impp={}",
                         sheet.getSheetName(),
                         Double.isNaN(pnennKWp) ? "MISSING" : pnennKWp,
                         Double.isNaN(pmppKW) ? "MISSING" : pmppKW,
                         Double.isNaN(vmppV) ? "MISSING" : vmppV,
                         Double.isNaN(imppA) ? "MISSING" : imppA);
            return null; // Return null if not all data was found/valid
        }
    }

    // --- Robust Cell Value Getters ---

    /** Gets cell value as String, evaluating formulas. Returns empty string for null/blank. */
    private String getCellValueAsString(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return "";
        }
        try {
             // Use formatter which handles different types and formula evaluation
             return formatter.formatCellValue(cell, evaluator).trim();
        } catch (Exception e) {
             // Log errors during formatting/evaluation (e.g., complex formula issues)
             logger.warn("Error formatting/evaluating cell {}: {}. Returning empty string.",
                         cell.getAddress(), e.getMessage());
             // Attempt to get cached value if formula evaluation failed
             if (cell.getCellType() == CellType.FORMULA) {
                 try { return cell.getStringCellValue().trim(); } catch (Exception ignored) {} // Try string
                 try { return String.valueOf(cell.getNumericCellValue()).trim(); } catch (Exception ignored) {} // Try numeric
             }
             return ""; // Fallback
        }
    }

     /** Gets cell value as double, evaluating formulas and attempting string parsing. Returns Double.NaN for errors/blanks. */
     private double getCellValueAsDouble(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return Double.NaN;
        }

        CellType cellType = cell.getCellType();

        // Handle formulas first
         if (evaluator != null && cellType == CellType.FORMULA) {
            try {
                CellValue evaluatedCell = evaluator.evaluate(cell);
                 cellType = evaluatedCell.getCellType(); // Use the result type

                 if (cellType == CellType.NUMERIC) return evaluatedCell.getNumberValue();
                 if (cellType == CellType.STRING) {
                     // Try parsing the string result of the formula
                     return parseDoubleWithOptionalUnit(evaluatedCell.getStringValue(), null); // No specific unit expected here
                 }
                 if (cellType == CellType.BOOLEAN) return evaluatedCell.getBooleanValue() ? 1.0 : 0.0;
                 // Handle errors (#N/A, #VALUE!, etc.) or blanks from formula result as NaN
                 if(cellType == CellType.ERROR) {
                     logger.warn("Formula in cell {} resulted in an error: {}", cell.getAddress(), FormulaError.forInt(evaluatedCell.getErrorValue()).getString());
                 }
                 return Double.NaN;

            } catch (Exception e) {
                 // Catch errors during formula evaluation itself
                 logger.warn("Could not evaluate formula in cell {}: {}. Returning NaN.", cell.getAddress(), e.getMessage());
                 return Double.NaN;
            }
        }

        // Handle non-formula cells
        switch (cellType) {
            case NUMERIC:
                return cell.getNumericCellValue();
            case STRING:
                 // Attempt to parse the string content as a double
                return parseDoubleWithOptionalUnit(cell.getStringCellValue(), null);
            case BOOLEAN:
                return cell.getBooleanCellValue() ? 1.0 : 0.0; // Treat TRUE as 1, FALSE as 0
            case ERROR:
                 logger.warn("Cell {} contains an error code: {}", cell.getAddress(), FormulaError.forInt(cell.getErrorCellValue()).getString());
                 return Double.NaN;
            // BLANK case already handled at the start
            default:
                 // Log unexpected cell types
                 logger.warn("Unhandled cell type {} in cell {}. Returning NaN.", cell.getCellType(), cell.getAddress());
                 return Double.NaN;
        }
    }

    /**
     * Parses a string value into a double, robustly handling common issues like
     * commas as decimal separators, optional units at the end, and non-numeric characters.
     *
     * @param valueStr The string to parse.
     * @param unitToRemove Optional unit string (case-insensitive) to remove from the end (e.g., "kwp", "v"). Can be null.
     * @return The parsed double value, or Double.NaN if parsing fails or input is null/empty/"-".
     */
    private double parseDoubleWithOptionalUnit(String valueStr, String unitToRemove) {
        if (valueStr == null || valueStr.trim().isEmpty() || valueStr.trim().equals("-")) {
            return Double.NaN; // Treat empty, null, or common placeholders as NaN
        }

        String cleanedValue = valueStr.trim().toLowerCase(); // Work with lowercase trimmed string

        // Remove optional unit from the end
        if (unitToRemove != null && !unitToRemove.isEmpty()) {
             String lowerUnit = unitToRemove.toLowerCase();
             if (cleanedValue.endsWith(lowerUnit)) {
                 cleanedValue = cleanedValue.substring(0, cleanedValue.length() - lowerUnit.length()).trim();
             }
        }

        // Standardize decimal separator (replace comma with dot)
        cleanedValue = cleanedValue.replace(',', '.');

        // Remove any characters that are not digits, decimal point, or a leading minus sign
        // Be careful not to remove minus sign if it's valid
         // Keep: digits (0-9), decimal point (.), potential leading minus (-)
         // Remove: thousands separators, currency symbols, other text
         // A more robust regex might be needed depending on possible inputs
         // This one keeps only digits, dot, and minus:
         cleanedValue = cleanedValue.replaceAll("[^\\d.-]", "");

        // Check if string became empty after cleaning
        if (cleanedValue.isEmpty() || cleanedValue.equals(".") || cleanedValue.equals("-")) {
             logger.trace("String became invalid ('{}') after cleaning unit/chars from original '{}'. Returning NaN.", cleanedValue, valueStr);
            return Double.NaN;
        }

        try {
            // Attempt final parsing
            return Double.parseDouble(cleanedValue);
        } catch (NumberFormatException e) {
             // Log parsing failures for debugging, but return NaN
             logger.trace("Could not parse double from cleaned string '{}' (original: '{}'). Error: {}", cleanedValue, valueStr, e.getMessage());
            return Double.NaN;
        }
    }
}