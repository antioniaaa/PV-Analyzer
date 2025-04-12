package de.anton.pv.analyser.pv_analyzer.service; // Beispiel-Package für Services

import de.anton.pv.analyser.pv_analyzer.model.ExcelData;
import de.anton.pv.analyser.pv_analyzer.model.ExcelReader; // Reader wird hier verwendet
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Objects;

/**
 * Service responsible for loading data from Excel files.
 */
public class ExcelDataService {

    private static final Logger logger = LoggerFactory.getLogger(ExcelDataService.class);
    private final ExcelReader excelReader;

    public ExcelDataService() {
        this.excelReader = new ExcelReader(); // Instantiate the reader internally
    }

    /**
     * Loads data from the specified Excel file.
     *
     * @param file The Excel file to load.
     * @return An ExcelData object containing the parsed data.
     * @throws IOException           If an error occurs during file reading or parsing.
     * @throws NullPointerException if the file is null.
     */
    public ExcelData loadDataFromFile(File file) throws IOException {
        Objects.requireNonNull(file, "Input file cannot be null.");
        logger.info("Data Service: Attempting to load Excel file: {}", file.getAbsolutePath());
        try {
            ExcelData data = excelReader.readExcel(file);
            logger.info("Data Service: Excel data loaded successfully from {}", file.getName());
            return data;
        } catch (IOException | RuntimeException e) {
            logger.error("Data Service: Failed to load or parse Excel file: {}", file.getAbsolutePath(), e);
            // Re-throw specific exception types if needed, or a general one
            throw new IOException("Fehler beim Lesen oder Verarbeiten der Excel-Datei: " + e.getMessage(), e);
        }
    }
}