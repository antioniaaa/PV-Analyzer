package de.anton.pv.analyser.pv_analyzer.controller;

import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel.AnalysisMode;
import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import de.anton.pv.analyser.pv_analyzer.model.ExcelData;
import de.anton.pv.analyser.pv_analyzer.model.ScalingType;
import de.anton.pv.analyser.pv_analyzer.model.TrackerInfo;
import de.anton.pv.analyser.pv_analyzer.service.AnalysisConfiguration;
import de.anton.pv.analyser.pv_analyzer.service.AnalysisService;
import de.anton.pv.analyser.pv_analyzer.service.ExcelDataService;
import de.anton.pv.analyser.pv_analyzer.model.ExcelExporter;
import de.anton.pv.analyser.pv_analyzer.model.ModuleInfo;
import de.anton.pv.analyser.pv_analyzer.model.ParameterEstimationUtils;
import de.anton.pv.analyser.pv_analyzer.view.MainView;
import de.anton.pv.analyser.pv_analyzer.view.ClusterPlotDialog;
import de.anton.pv.analyser.pv_analyzer.view.OutlierDialog;
import de.anton.pv.analyser.pv_analyzer.view.TableDialog;
import de.anton.pv.analyser.pv_analyzer.view.HierarchicalClusterDialog;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.plot.XYPlot;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ItemEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Comparator;
import java.util.stream.Collectors;

/**
 * Controller using Services for modularity. Handles UI events, manages background tasks,
 * updates Model based on Service results, and signals View updates.
 */
public class AppController implements PropertyChangeListener {

    private static final Logger logger = LoggerFactory.getLogger(AppController.class);

    private final AnalysisModel analysisModel;
    private final MainView mainView;
    private final ExcelDataService dataService;
    private final AnalysisService analysisService;

    private volatile boolean isUpdatingComboBox = false;
    private JDialog progressDialog;
    private JProgressBar progressBar;
    private JLabel progressLabel;
    private JButton cancelButton;
    private volatile SwingWorker<?, ?> activeWorker = null;

    private TableDialog tableDialog = null;
    private ClusterPlotDialog plotDialog = null;
    private OutlierDialog outlierDialog = null;
    private HierarchicalClusterDialog hierarchyDialog = null;

    private static final DecimalFormat EPSILON_FORMAT = new DecimalFormat("#0.0000");
    private static final SimpleDateFormat DATE_FORMAT_PARSER = new SimpleDateFormat("dd.MM.yyyy HH:mm");
    static { DATE_FORMAT_PARSER.setLenient(false); }

    public AppController(AnalysisModel model, MainView view) {
        this.analysisModel = Objects.requireNonNull(model);
        this.mainView = Objects.requireNonNull(view);
        this.dataService = new ExcelDataService();
        this.analysisService = new AnalysisService();
        this.analysisModel.addPropertyChangeListener(this);
        initializeListeners();
        updateViewInitialState();
        createProgressDialog();
        logger.debug("AppController initialized with Services.");
    }

    private void initializeListeners() {
        logger.debug("Initializing UI listeners...");
        try {
            mainView.getLoadFileButton().addActionListener(e -> handleLoadFile());
            mainView.getSingleTimestampRadioButton().addActionListener(this::handleModeChange);
            mainView.getIntervalRadioButton().addActionListener(this::handleModeChange);
            mainView.getTimestampComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) updateModelTimestampSelection(); });
            mainView.getIntervalStartComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) { validateIntervalSelection(); updateModelIntervalSelection(); } });
            mainView.getIntervalEndComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) { validateIntervalSelection(); updateModelIntervalSelection(); } });
            mainView.getXVariableComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) updateModelVariableSelection(); });
            mainView.getYVariableComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) updateModelVariableSelection(); });
            mainView.getApplyParamsButton().addActionListener(e -> updateParametersAndRunAnalysis());
            mainView.getOpticsScalingComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) updateModelScalingSelection(); });
            mainView.getDbscanScalingComboBox().addItemListener(e -> { if (e.getStateChange() == ItemEvent.SELECTED && !isUpdatingComboBox) updateModelScalingSelection(); });
            mainView.getEstimateParamsButton().addActionListener(e -> handleEstimateParameters());
            mainView.getShowTableButton().addActionListener(e -> showDataDialog());
            mainView.getShowPlotButton().addActionListener(e -> showPlotDialog());
            mainView.getShowOutliersButton().addActionListener(e -> showOutlierDialog());
            mainView.getShowHierarchyButton().addActionListener(e -> showHierarchicalClusterView());
            mainView.getExportExcelButton().addActionListener(e -> handleExportExcel());
            logger.debug("UI listeners initialized.");
       
        } catch (Exception e) { logger.error("Unexpected error initializing UI listeners: {}", e.getMessage(), e); }
    }

    private void updateViewInitialState() { logger.debug("Setting initial view state."); try { SwingUtilities.invokeLater(() -> { isUpdatingComboBox = true; try { updateTimestampList(null); mainView.getOpticsEpsilonTextField().setText(String.valueOf(analysisModel.getOpticsEpsilon())); mainView.getOpticsMinPtsTextField().setText(String.valueOf(analysisModel.getOpticsMinPts())); mainView.getDbscanEpsilonTextField().setText(String.valueOf(analysisModel.getDbscanEpsilon())); mainView.getDbscanMinPtsTextField().setText(String.valueOf(analysisModel.getDbscanMinPts())); mainView.getOpticsScalingComboBox().setSelectedItem(analysisModel.getOpticsScalingType()); mainView.getDbscanScalingComboBox().setSelectedItem(analysisModel.getDbscanScalingType()); mainView.getXVariableComboBox().setSelectedItem(analysisModel.getSelectedXVariable()); mainView.getYVariableComboBox().setSelectedItem(analysisModel.getSelectedYVariable()); AnalysisMode initialMode = analysisModel.getCurrentMode(); mainView.getSingleTimestampRadioButton().setSelected(initialMode == AnalysisMode.SINGLE_TIMESTAMP); mainView.getIntervalRadioButton().setSelected(initialMode == AnalysisMode.MAX_VECTOR_INTERVAL); mainView.updateControlStates(false, initialMode); mainView.setStatusLabel("Bereit. Bitte Excel-Datei laden."); } finally { isUpdatingComboBox = false; } }); } catch (Exception e) { logger.error("Error setting initial view state: {}", e.getMessage(), e); } }
    private void createProgressDialog() { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::createProgressDialog); return; } if (progressDialog == null) { progressDialog = new JDialog(mainView, "Verarbeitung", true); progressBar = new JProgressBar(); progressBar.setIndeterminate(true); progressBar.setStringPainted(true); progressBar.setString("Initialisiere..."); progressLabel = new JLabel("Bitte warten...", SwingConstants.CENTER); cancelButton = new JButton("Abbrechen"); cancelButton.setToolTipText("Versucht, den aktuellen Vorgang abzubrechen."); cancelButton.addActionListener(e -> handleCancelAction()); JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER)); buttonPanel.add(cancelButton); JPanel panel = new JPanel(new BorderLayout(10, 10)); panel.setBorder(BorderFactory.createEmptyBorder(20, 20, 10, 20)); panel.add(progressLabel, BorderLayout.NORTH); panel.add(progressBar, BorderLayout.CENTER); panel.add(buttonPanel, BorderLayout.SOUTH); progressDialog.setContentPane(panel); progressDialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE); progressDialog.setResizable(false); progressDialog.pack(); progressDialog.setMinimumSize(new Dimension(350, progressDialog.getPreferredSize().height)); progressDialog.setLocationRelativeTo(mainView); logger.trace("Progress dialog created."); } }
    private void handleCancelAction() { SwingWorker<?, ?> workerToCancel = this.activeWorker; if (workerToCancel != null && !workerToCancel.isDone()) { logger.info("Cancel requested for worker {}", workerToCancel.getClass().getSimpleName()); boolean requested = workerToCancel.cancel(true); logger.info("Worker cancel request result: {}", requested); if (requested) { mainView.setStatusLabel("Vorgang wird abgebrochen..."); hideProgressDialog(); } else { logger.warn("Cancellation request failed or worker finished too quickly."); hideProgressDialog(); } } else { logger.warn("Cancel clicked but no active worker or worker already done."); hideProgressDialog(); } }
    private void showProgressDialog(String message, SwingWorker<?, ?> worker) { if (!SwingUtilities.isEventDispatchThread()) { SwingWorker<?, ?> finalWorker = worker; SwingUtilities.invokeLater(() -> showProgressDialog(message, finalWorker)); return; } createProgressDialog(); this.activeWorker = worker; logger.debug("Showing progress for {}: {}", worker.getClass().getSimpleName(), message); progressBar.setString(message != null ? message : "..."); progressLabel.setText(message != null ? message : "..."); cancelButton.setEnabled(true); progressDialog.pack(); progressDialog.setLocationRelativeTo(mainView); mainView.setBusyState(true); progressDialog.setVisible(true); }
    private void hideProgressDialog() { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::hideProgressDialog); return; } if (progressDialog != null && progressDialog.isVisible()) { logger.debug("Hiding progress dialog."); progressDialog.setVisible(false); } this.activeWorker = null; mainView.setBusyState(false); }

    private static class ExcelDataLoadResult { boolean success = false; boolean cancelled = false; Throwable error = null; long durationNanos = -1; ExcelData loadedData = null; boolean isSuccess() { return success && !cancelled && error == null; } ExcelDataLoadResult setSuccess(boolean success, ExcelData data) { this.success = success; this.loadedData = data; return this; } boolean isCancelled() { return cancelled; } ExcelDataLoadResult setCancelled() { this.cancelled = true; this.success = false; return this; } Throwable getError() { return error; } ExcelDataLoadResult setError(Throwable error) { this.error = error; this.success = false; return this; } long getDurationNanos() { return durationNanos; } ExcelDataLoadResult setDurationNanos(long durationNanos) { this.durationNanos = durationNanos; return this; } ExcelData getData() { return loadedData;} }
    private void handleLoadFile() { logger.debug("handleLoadFile triggered."); JFileChooser fileChooser = mainView.getFileChooser(); if (fileChooser == null) return; int rv = fileChooser.showOpenDialog(mainView); if (rv == JFileChooser.APPROVE_OPTION) { File file = fileChooser.getSelectedFile(); if (file == null || !file.isFile() || !file.canRead()) { showErrorDialogOnEDT("Datei ungültig/nicht lesbar."); return; } logger.info("File selected: {}", file.getAbsolutePath()); mainView.setStatusLabel("Lade Datei..."); SwingWorker<ExcelDataLoadResult, Void> loadWorker = new SwingWorker<>(){ @Override protected ExcelDataLoadResult doInBackground() throws Exception { logger.trace("Load worker doInBackground started."); long start = System.nanoTime(); ExcelDataLoadResult result = new ExcelDataLoadResult(); try { if (isCancelled()) return result.setCancelled(); ExcelData data = dataService.loadDataFromFile(file); if (isCancelled()) return result.setCancelled(); result.setSuccess(true, data); } catch (Exception e) { result.setError(e); logger.error("Error loading Excel in background", e); } finally { result.setDurationNanos(System.nanoTime() - start); } return result; } @Override protected void done() { logger.debug("Load worker 'done' executing on EDT..."); ExcelDataLoadResult result = null; try { if (isCancelled()) { logger.info("Load task cancelled by user."); mainView.setStatusLabel("Ladevorgang abgebrochen."); hideProgressDialog(); return; } result = get(10, TimeUnit.SECONDS); } catch (Exception e) { logger.error("Error getting load worker result", e); if (result == null) result = new ExcelDataLoadResult(); if (result.getError() == null) result.setError(e instanceof ExecutionException ? e.getCause() : e); } finally { hideProgressDialog(); } if (result != null && !result.isCancelled()) { if (result.isSuccess() && result.getData() != null) { analysisModel.setDataAndFile(result.getData(), file); long ms = TimeUnit.NANOSECONDS.toMillis(result.getDurationNanos()); logger.info("Load successful in ~{} ms.", ms); mainView.setStatusLabel("Datei '" + file.getName() + "' geladen (" + ms + " ms). Konfiguration wählen."); } else { analysisModel.setDataAndFile(null, null); Throwable error = result.getError() != null ? result.getError() : new RuntimeException("Unknown load error"); showErrorDialogOnEDT("Fehler beim Laden der Datei:\n" + formatErrorMessage(error)); mainView.setStatusLabel("Fehler beim Laden."); } } logger.debug("Load worker 'done' finished."); } }; this.activeWorker = loadWorker; loadWorker.execute(); showProgressDialog("Lade Datei: " + file.getName(), loadWorker); } else { logger.debug("File selection cancelled."); } }
    private void handleModeChange(ActionEvent e) { AnalysisMode newMode = mainView.getSingleTimestampRadioButton().isSelected() ? AnalysisMode.SINGLE_TIMESTAMP : AnalysisMode.MAX_VECTOR_INTERVAL; logger.info("Mode selection changed to: {}", newMode); mainView.updateControlStates(analysisModel.isDataLoaded(), newMode); analysisModel.setAnalysisMode(newMode); }

    /** Updates model state based on the single timestamp selection. */
    private void updateModelTimestampSelection() {
        if (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP) {
            String selectedTimestamp = (String) mainView.getTimestampComboBox().getSelectedItem();
            try { if (!Objects.equals(selectedTimestamp, analysisModel.getSelectedTimestamp())) analysisModel.setSelectedTimestamp(selectedTimestamp); }
            catch (IllegalArgumentException e) { logger.warn("Invalid timestamp selection ignored in model update: {}", selectedTimestamp); }
        }
        updateAnalysisStatus();
    }

    /** Updates model state based on the interval selection. */
    private void updateModelIntervalSelection() {
        if (analysisModel.getCurrentMode() == AnalysisMode.MAX_VECTOR_INTERVAL) {
            String startStr = (String) mainView.getIntervalStartComboBox().getSelectedItem();
            String endStr = (String) mainView.getIntervalEndComboBox().getSelectedItem();
            if (startStr != null && endStr != null && validateIntervalOrder(startStr, endStr)) {
                 try { if (!Objects.equals(startStr, analysisModel.getIntervalStartTimestamp()) || !Objects.equals(endStr, analysisModel.getIntervalEndTimestamp())) analysisModel.setIntervalTimestamps(startStr, endStr); }
                 catch (IllegalArgumentException e) { logger.error("Error setting interval timestamps: {}", e.getMessage()); }
            } else { logger.debug("Invalid interval selection, model not updated yet."); }
        }
        updateAnalysisStatus();
    }

    /** Updates model state based on variable selection. */
    private void updateModelVariableSelection() { String selectedX = (String) mainView.getXVariableComboBox().getSelectedItem(); String selectedY = (String) mainView.getYVariableComboBox().getSelectedItem(); if (selectedX == null || selectedY == null) return; boolean xChanged = !selectedX.equals(analysisModel.getSelectedXVariable()); boolean yChanged = !selectedY.equals(analysisModel.getSelectedYVariable()); if (!xChanged && !yChanged) return; logger.info("Analysis variables changing (model update only): X='{}', Y='{}'", selectedX, selectedY); try { if (xChanged) analysisModel.setSelectedXVariableDirect(selectedX); if (yChanged) analysisModel.setSelectedYVariableDirect(selectedY); } catch (IllegalArgumentException e) { showErrorDialogOnEDT("Fehler beim Setzen der Variable: " + e.getMessage()); } updateAnalysisStatus(); }
    /** Updates model state based on scaling selection. */
    private void updateModelScalingSelection() { ScalingType opticsScale = (ScalingType) mainView.getOpticsScalingComboBox().getSelectedItem(); ScalingType dbscanScale = (ScalingType) mainView.getDbscanScalingComboBox().getSelectedItem(); boolean oChanged = !Objects.equals(opticsScale, analysisModel.getOpticsScalingType()); boolean dChanged = !Objects.equals(dbscanScale, analysisModel.getDbscanScalingType()); if (!oChanged && !dChanged) return; logger.info("Scaling changing (model update only): OPTICS={}, DBSCAN={}", opticsScale, dbscanScale); try { if(oChanged) analysisModel.setOpticsScalingTypeDirect(opticsScale); if(dChanged) analysisModel.setDbscanScalingTypeDirect(dbscanScale); } catch (IllegalArgumentException e) { showErrorDialogOnEDT("Fehler beim Setzen der Skalierung: " + e.getMessage()); } updateAnalysisStatus(); }
    private void handleOpticsScalingChange(ScalingType type) { updateModelScalingSelection(); } // Update model only
    private void handleDbscanScalingChange(ScalingType type) { updateModelScalingSelection(); } // Update model only


    /** Reads config, updates model parameters, and runs analysis worker. Called by Apply button. */
    private void updateParametersAndRunAnalysis() {
         logger.info("Updating parameters from UI and triggering analysis.");
         final String opticsEpsStr = mainView.getOpticsEpsilonTextField().getText(); final String opticsMinPtsStr = mainView.getOpticsMinPtsTextField().getText(); final String dbscanEpsStr = mainView.getDbscanEpsilonTextField().getText(); final String dbscanMinPtsStr = mainView.getDbscanMinPtsTextField().getText();
         double opticsEps, dbscanEps; int opticsMinPts, dbscanMinPts;
         try { opticsEps = parseDoubleParam(opticsEpsStr); opticsMinPts = parseIntParam(opticsMinPtsStr); dbscanEps = parseDoubleParam(dbscanEpsStr); dbscanMinPts = parseIntParam(dbscanMinPtsStr); if (opticsEps <= 0 || opticsMinPts <= 0 || dbscanEps <= 0 || dbscanMinPts <= 0) throw new IllegalArgumentException("Parameter müssen positiv sein."); }
         catch (IllegalArgumentException e) { showErrorDialogOnEDT("Ungültiger Parameterwert: " + e.getMessage()); return; }

         // Update Model Parameters (Setters should NOT trigger analysis here)
         try {
             analysisModel.setOpticsParameters(opticsEps, opticsMinPts);
             analysisModel.setDbscanParameters(dbscanEps, dbscanMinPts);
             logger.debug("Model parameters updated via direct setters.");
         } catch (IllegalArgumentException e) { showErrorDialogOnEDT("Fehler beim Setzen der Parameter: " + e.getMessage()); return; }
         // InterruptedException removed from setters

         // NOW, trigger the analysis with the current complete configuration
         triggerAnalysisIfReady("Apply Button");
    }

    /** Triggers analysis via a background worker if the configuration is valid. */
    private void triggerAnalysisIfReady(String triggerSource) {
        logger.debug("TriggerAnalysisIfReady called by: {}", triggerSource);
        // Update model state just before check, ensuring UI values are considered for the current mode
        updateModelTimestampSelection(); // Update timestamp OR interval from UI
        updateModelIntervalSelection();
        // Variables and Scaling are assumed to be up-to-date via their listeners calling direct model setters

        if (!analysisModel.isAnalysisConfigured()) {
            logger.warn("Analysis not triggered: Configuration invalid for mode {} after update from UI.", analysisModel.getCurrentMode());
            // Show specific error if possible
            if (analysisModel.getCurrentMode() == AnalysisMode.MAX_VECTOR_INTERVAL && (analysisModel.getIntervalStartTimestamp() == null || analysisModel.getIntervalEndTimestamp() == null)) {
                showErrorDialogOnEDT("Bitte wählen Sie ein gültiges Start- und End-Datum für das Intervall.");
            } else if (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP && analysisModel.getSelectedTimestamp() == null) {
                 showErrorDialogOnEDT("Bitte wählen Sie einen gültigen Zeitstempel aus.");
            } else {
                 showErrorDialogOnEDT("Analyse nicht korrekt konfiguriert.");
            }
            updateAnalysisStatus(); // Ensure UI reflects inability to run
            return;
        }

        String taskDesc = (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP)
                          ? "Analyse für Zeitstempel '" + analysisModel.getSelectedTimestamp() + "'"
                          : "Analyse für Intervall [" + analysisModel.getIntervalStartTimestamp() + "..." + analysisModel.getIntervalEndTimestamp() + "]";
        mainView.setStatusLabel(taskDesc + " wird ausgeführt...");

        final AnalysisConfiguration config = getCurrentAnalysisConfiguration();
        if (config == null) { logger.error("Configuration is null! Aborting."); showErrorDialogOnEDT("Interner Fehler: Analysekonfiguration konnte nicht erstellt werden."); return; }

        SwingWorker<AnalysisService.AnalysisResult, Void> worker = createAnalysisWorker(taskDesc, config);
        this.activeWorker = worker;
        worker.execute();
        showProgressDialog(taskDesc + "...", worker);
    }


    private double parseDoubleParam(String text) throws NumberFormatException { try { return Double.parseDouble(text.replace(',', '.').trim()); } catch (NullPointerException | NumberFormatException e) { throw new NumberFormatException("Ungültige Dezimalzahl: '" + text + "'"); } }
    private int parseIntParam(String text) throws NumberFormatException { try { return Integer.parseInt(text.trim()); } catch (NullPointerException | NumberFormatException e) { throw new NumberFormatException("Ungültige Ganzzahl: '" + text + "'"); } }
    private void handleExportExcel() { logger.debug("handleExportExcel triggered."); if (!analysisModel.isAnalysisDataAvailable()) { showErrorDialogOnEDT("Keine Analysedaten zum Exportieren verfügbar."); return; } File inputFile = analysisModel.getLastLoadedFile(); if (inputFile == null) { showErrorDialogOnEDT("Speicherort der Originaldatei nicht bekannt."); return; } File outputDirectory = inputFile.getParentFile(); if (outputDirectory == null || !outputDirectory.isDirectory()) { showErrorDialogOnEDT("Verzeichnis der Originaldatei nicht gefunden."); return; } Path outputDirPath = outputDirectory.toPath(); if (!Files.isWritable(outputDirPath)) { showErrorDialogOnEDT("Keine Schreibrechte im Verzeichnis:\n" + outputDirectory.getAbsolutePath()); return; } String baseName = inputFile.getName(); int dotIndex = baseName.lastIndexOf('.'); if (dotIndex > 0) baseName = baseName.substring(0, dotIndex); String sourceDesc = (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP) ? analysisModel.getSelectedTimestamp() : "Interval_" + analysisModel.getIntervalStartTimestamp() + "_to_" + analysisModel.getIntervalEndTimestamp(); if (sourceDesc == null) { showErrorDialogOnEDT("Zeitstempel/Intervall nicht gesetzt."); return; } String safeSourceDesc = sourceDesc.replaceAll("[^a-zA-Z0-9.-]", "_").replace(":", "-"); String outputFileName = String.format("%s_Analyse_%s.xlsx", baseName, safeSourceDesc); Path outputPath = outputDirPath.resolve(outputFileName); final String finalOutputPath = outputPath.toString(); logger.info("Preparing to export analysis data to: {}", finalOutputPath); mainView.setStatusLabel("Exportiere Daten nach " + outputFileName + "..."); SwingWorker<Boolean, Void> exportWorker = new SwingWorker<>() { private Exception exportError = null; @Override protected Boolean doInBackground() throws Exception { logger.debug("Export worker doInBackground started."); try { if (isCancelled()) return false; ExcelExporter exporter = new ExcelExporter(); List<CalculatedDataPoint> dataToExport = analysisModel.getCurrentAnalysisData(); exporter.exportData(dataToExport, finalOutputPath, analysisModel.hasModuleInfo()); if (isCancelled()) return false; logger.debug("Export worker finished successfully in background."); return true; } catch (InterruptedException e) { exportError = e; Thread.currentThread().interrupt(); logger.info("Export worker interrupted."); return false; } catch (Exception e) { exportError = e; logger.error("Error during Excel export in background", e); return false; } } @Override protected void done() { logger.debug("Export worker 'done' executing on EDT..."); Boolean success = false; try { if (isCancelled()) { logger.info("Export task cancelled."); mainView.setStatusLabel("Export abgebrochen."); hideProgressDialog(); try { Files.deleteIfExists(outputPath); } catch (IOException ex) { /* ignore */ } return; } success = get(30, TimeUnit.SECONDS); } catch (Exception e) { logger.error("Error getting export worker result", e); if (exportError == null) exportError = e instanceof ExecutionException ? (Exception)e.getCause() : e; } finally { hideProgressDialog(); } if (success) { logger.info("Export successful."); mainView.setStatusLabel("Analyse exportiert: " + outputFileName); showInfoDialogOnEDT("Daten exportiert nach:\n" + finalOutputPath); } else { logger.error("Export failed."); String errorMsg = formatErrorMessage(exportError); showErrorDialogOnEDT("Fehler beim Exportieren:\n" + errorMsg); mainView.setStatusLabel("Export fehlgeschlagen."); } updateAnalysisStatus(); } }; this.activeWorker = exportWorker; exportWorker.execute(); showProgressDialog("Exportiere Daten...", exportWorker); }
    private void handleEstimateParameters() { logger.info("Parameter estimation triggered."); if (!analysisModel.isDataLoaded()) { showErrorDialogOnEDT("Bitte zuerst Daten laden."); return; } int opticsK, dbscanK; try { opticsK = Math.max(1, parseIntParam(mainView.getOpticsMinPtsTextField().getText()) - 1); dbscanK = Math.max(1, parseIntParam(mainView.getDbscanMinPtsTextField().getText()) - 1); } catch (NumberFormatException e) { showErrorDialogOnEDT("Ungültiger MinPts-Wert."); return; } ScalingType opticsScale = (ScalingType) mainView.getOpticsScalingComboBox().getSelectedItem(); ScalingType dbscanScale = (ScalingType) mainView.getDbscanScalingComboBox().getSelectedItem(); List<CalculatedDataPoint> pointsForEstimation = analysisModel.getCurrentAnalysisData(); if (pointsForEstimation.isEmpty()) { if (analysisModel.isDataLoaded() && !analysisModel.getTimestamps().isEmpty()) { showErrorDialogOnEDT("Bitte führen Sie zuerst eine Analyse aus, um Daten für die Schätzung zu generieren."); return; } else { showErrorDialogOnEDT("Keine Daten für Schätzung verfügbar (laden/konfigurieren)."); return; } } List<CalculatedDataPoint> validPoints = pointsForEstimation.stream().filter(p -> p != null && !Double.isNaN(analysisModel.getXExtractor().apply(p)) && !Double.isNaN(analysisModel.getYExtractor().apply(p))).collect(Collectors.toList()); if (validPoints.size() <= Math.max(opticsK, dbscanK)) { showInfoDialogOnEDT("Nicht genügend valide Datenpunkte ("+ validPoints.size() + ") für k-Distanz."); return; } SwingWorker<Map<String, List<Double>>, Void> estimationWorker = new SwingWorker<>() { private Exception calcError = null; @Override protected Map<String, List<Double>> doInBackground() throws Exception { /* ... uses analysisModel.calculateKDistances ... */ logger.debug("Starting k-distance calculation worker..."); Map<String, List<Double>> results = new HashMap<>(); try { if (isCancelled()) return null; logger.info("Calculating k-Dist for OPTICS (k={}, scale={})...", opticsK, opticsScale); List<Double> opticsDistances = analysisModel.calculateKDistances(opticsK, validPoints, opticsScale, "OPTICS"); results.put("OPTICS", opticsDistances); logger.info("OPTICS k-Dist calculation finished ({} distances).", opticsDistances != null ? opticsDistances.size(): 0); if (isCancelled()) return null; logger.info("Calculating k-Dist for DBSCAN (k={}, scale={})...", dbscanK, dbscanScale); List<Double> dbscanDistances = analysisModel.calculateKDistances(dbscanK, validPoints, dbscanScale, "DBSCAN"); results.put("DBSCAN", dbscanDistances); logger.info("DBSCAN k-Dist calculation finished ({} distances).", dbscanDistances != null ? dbscanDistances.size() : 0); } catch (InterruptedException e) { calcError = e; Thread.currentThread().interrupt(); logger.info("k-Distance calculation interrupted."); } catch (Exception e) { calcError = e; logger.error("Error during k-distance calculation", e); } return results; } @Override protected void done() { /* ... shows estimation dialog ... */ logger.debug("k-Distance calculation worker done."); hideProgressDialog(); Map<String, List<Double>> results = null; try { if (isCancelled()) { logger.info("k-Distance calculation cancelled."); mainView.setStatusLabel("Parameter-Schätzung abgebrochen."); return; } results = get(1, TimeUnit.MINUTES); } catch (Exception e) { logger.error("Error getting k-dist worker result", e); if (calcError == null) calcError = e instanceof ExecutionException ? (Exception)e.getCause() : e; } if (calcError != null) { showErrorDialogOnEDT("Fehler bei der k-Distanz-Berechnung:\n" + formatErrorMessage(calcError)); mainView.setStatusLabel("Fehler bei Parameter-Schätzung."); } else if (results != null && (!results.isEmpty() || (results.containsKey("OPTICS") || results.containsKey("DBSCAN")) )) { logger.info("k-Distance calculation successful, showing results dialog."); mainView.setStatusLabel("k-Distanz-Graphen berechnet."); showParameterEstimationDialog(results.get("OPTICS"), results.get("DBSCAN"), opticsK, dbscanK, opticsScale, dbscanScale); } else { showErrorDialogOnEDT("k-Distanz-Berechnung lieferte keine Ergebnisse."); mainView.setStatusLabel("Parameter-Schätzung fehlgeschlagen."); } } }; this.activeWorker = estimationWorker; estimationWorker.execute(); showProgressDialog("Berechne k-Distanz Graphen...", estimationWorker); }
    private void showParameterEstimationDialog(List<Double> opticsDistances, List<Double> dbscanDistances, int opticsK, int dbscanK, ScalingType opticsScale, ScalingType dbscanScale) { /* ... unverändert ... */ JDialog estimationDialog = new JDialog(mainView, "Parameter Schätzung (k-Distanz Graphen)", true); estimationDialog.setLayout(new BorderLayout(10, 10)); estimationDialog.setSize(850, 650); estimationDialog.setLocationRelativeTo(mainView); JLabel instructionLabel = new JLabel( "<html>Identifizieren Sie den 'Ellenbogen' in jeder Kurve (Punkt mit starkem Anstieg).<br>" + "Der Y-Wert (Distanz) an diesem Punkt ist ein guter Startwert für Epsilon.<br>" + "Klicken Sie auf 'Übernehmen', um den Wert in das Hauptfenster zu kopieren.<br>" + "Empfehlung für MinPts: OPTICS >= 3, DBSCAN (pro Gruppe) >= 2.</html>", SwingConstants.CENTER); instructionLabel.setBorder(BorderFactory.createEmptyBorder(10,10,10,10)); estimationDialog.add(instructionLabel, BorderLayout.NORTH); JPanel centerPanel = new JPanel(new GridLayout(1, 2, 15, 0)); centerPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10)); JPanel opticsPanel = new JPanel(new BorderLayout(5, 5)); opticsPanel.setBorder(BorderFactory.createTitledBorder("OPTICS (k=" + opticsK + ", Scale=" + opticsScale + ")")); double estimatedOpticsEps = -1.0; if (opticsDistances != null && !opticsDistances.isEmpty()) { JFreeChart opticsChart = createKDistanceChart(opticsDistances, "OPTICS k-Distanz"); ChartPanel opticsChartPanel = new ChartPanel(opticsChart); opticsChartPanel.setMouseWheelEnabled(true); opticsPanel.add(opticsChartPanel, BorderLayout.CENTER); estimatedOpticsEps = ParameterEstimationUtils.findKneePointValue(opticsDistances); } else { opticsPanel.add(new JLabel("Keine Daten für OPTICS k-Distanz.", SwingConstants.CENTER), BorderLayout.CENTER); } JPanel opticsEstimatePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); JLabel opticsEstimateLabel = new JLabel("Geschätztes ε:"); JTextField opticsEstimateField = new JTextField(EPSILON_FORMAT.format(estimatedOpticsEps > 0 ? estimatedOpticsEps : 0), 8); opticsEstimateField.setEditable(false); opticsEstimateField.setToolTipText("Automatisch geschätzter Epsilon-Wert."); JButton applyOpticsButton = new JButton("Übernehmen"); applyOpticsButton.setToolTipText("Kopiert Wert in OPTICS Epsilon Feld."); applyOpticsButton.setEnabled(estimatedOpticsEps > 0); applyOpticsButton.addActionListener(e -> { mainView.getOpticsEpsilonTextField().setText(opticsEstimateField.getText()); logger.info("Applied estimated OPTICS Epsilon: {}", opticsEstimateField.getText()); }); opticsEstimatePanel.add(opticsEstimateLabel); opticsEstimatePanel.add(opticsEstimateField); opticsEstimatePanel.add(applyOpticsButton); opticsPanel.add(opticsEstimatePanel, BorderLayout.SOUTH); centerPanel.add(opticsPanel); JPanel dbscanPanel = new JPanel(new BorderLayout(5, 5)); dbscanPanel.setBorder(BorderFactory.createTitledBorder("DBSCAN (k=" + dbscanK + ", Scale=" + dbscanScale + ")")); double estimatedDbscanEps = -1.0; if (dbscanDistances != null && !dbscanDistances.isEmpty()) { JFreeChart dbscanChart = createKDistanceChart(dbscanDistances, "DBSCAN k-Distanz"); ChartPanel dbscanChartPanel = new ChartPanel(dbscanChart); dbscanChartPanel.setMouseWheelEnabled(true); dbscanPanel.add(dbscanChartPanel, BorderLayout.CENTER); estimatedDbscanEps = ParameterEstimationUtils.findKneePointValue(dbscanDistances); } else { dbscanPanel.add(new JLabel("Keine Daten für DBSCAN k-Distanz.", SwingConstants.CENTER), BorderLayout.CENTER); } JPanel dbscanEstimatePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT)); JLabel dbscanEstimateLabel = new JLabel("Geschätztes ε:"); JTextField dbscanEstimateField = new JTextField(EPSILON_FORMAT.format(estimatedDbscanEps > 0 ? estimatedDbscanEps : 0), 8); dbscanEstimateField.setEditable(false); dbscanEstimateField.setToolTipText("Automatisch geschätzter Epsilon-Wert."); JButton applyDbscanButton = new JButton("Übernehmen"); applyDbscanButton.setToolTipText("Kopiert Wert in DBSCAN Epsilon Feld."); applyDbscanButton.setEnabled(estimatedDbscanEps > 0); applyDbscanButton.addActionListener(e -> { mainView.getDbscanEpsilonTextField().setText(dbscanEstimateField.getText()); logger.info("Applied estimated DBSCAN Epsilon: {}", dbscanEstimateField.getText()); }); dbscanEstimatePanel.add(dbscanEstimateLabel); dbscanEstimatePanel.add(dbscanEstimateField); dbscanEstimatePanel.add(applyDbscanButton); dbscanPanel.add(dbscanEstimatePanel, BorderLayout.SOUTH); centerPanel.add(dbscanPanel); estimationDialog.add(centerPanel, BorderLayout.CENTER); JButton closeButton = new JButton("Schließen"); closeButton.addActionListener(e -> estimationDialog.dispose()); JPanel southPanel = new JPanel(new FlowLayout(FlowLayout.CENTER)); southPanel.setBorder(BorderFactory.createEmptyBorder(5, 0, 5, 0)); southPanel.add(closeButton); estimationDialog.add(southPanel, BorderLayout.SOUTH); estimationDialog.setVisible(true); }
    private JFreeChart createKDistanceChart(List<Double> distances, String title) { /* ... unverändert ... */ XYSeries series = new XYSeries("k-Distanz"); for (int i = 0; i < distances.size(); i++) { series.add(i + 1, distances.get(i)); } XYSeriesCollection dataset = new XYSeriesCollection(series); JFreeChart chart = ChartFactory.createXYLineChart(title, "Punkte (sortiert nach Distanz)", "Distanz zum k-ten Nachbarn", dataset); XYPlot plot = chart.getXYPlot(); plot.setBackgroundPaint(Color.WHITE); plot.setDomainGridlinePaint(Color.LIGHT_GRAY); plot.setRangeGridlinePaint(Color.LIGHT_GRAY); plot.getRenderer().setSeriesStroke(0, new BasicStroke(1.5f)); return chart; }

    /** --- KORRIGIERT: SwingWorker Factory Method für Analyse-Aufgaben --- */
    private SwingWorker<AnalysisService.AnalysisResult, Void> createAnalysisWorker(String taskDescription, AnalysisConfiguration config) {
        return new SwingWorker<AnalysisService.AnalysisResult, Void>() {
            private Throwable workerError = null;
            @Override protected AnalysisService.AnalysisResult doInBackground() throws Exception { logger.debug("Starting background analysis task: '{}'", taskDescription); long startTime = System.nanoTime(); try { if (isCancelled()) return null; return analysisService.runFullAnalysis(config); } catch (InterruptedException e) { workerError = e; Thread.currentThread().interrupt(); logger.info("Background task '{}' interrupted.", taskDescription); return null; } catch (Throwable e) { workerError = e; logger.error("Error during background task '{}'", taskDescription, e); return null; } finally { long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startTime); logger.debug("Background task finished (worker): '{}'. Duration: {} ms.", taskDescription, durationMs); } }
            @Override protected void done() { logger.debug("Worker 'done' on EDT for task: '{}'", taskDescription); AnalysisService.AnalysisResult analysisResult = null; Throwable finalError = workerError; try { if (isCancelled()) { logger.info("Task '{}' was cancelled.", taskDescription); mainView.setStatusLabel("Abgebrochen: " + taskDescription); hideProgressDialog(); updateAnalysisStatus(); return; } analysisResult = get(2, TimeUnit.MINUTES); } catch (ExecutionException e) { logger.error("ExecutionException getting result for '{}'", taskDescription, e); if (finalError == null) finalError = e.getCause() != null ? e.getCause() : e; } catch (InterruptedException e) { logger.warn("Interrupted getting result for '{}'", taskDescription, e); Thread.currentThread().interrupt(); if (finalError == null) finalError = e; } catch (TimeoutException e) { logger.error("Timeout getting result for '{}'", taskDescription, e); if (finalError == null) finalError = e; } catch (Exception e) { logger.error("Unexpected error in done() for '{}'", taskDescription, e); if (finalError == null) finalError = e; } finally { hideProgressDialog(); } if (finalError != null) { String message = formatErrorMessage(finalError); showErrorDialogOnEDT("Fehler während '" + taskDescription + "':\n" + message); mainView.setStatusLabel("Fehler bei: " + taskDescription); analysisModel.updateAnalysisResults(null); updateAnalysisStatus(); } else if (analysisResult != null) { logger.info("Task '{}' completed successfully. Updating model.", taskDescription); analysisModel.updateAnalysisResults(analysisResult); /* UI update via property change */ } else { logger.warn("Analysis task '{}' finished without error but produced no result (skipped?). Updating UI status.", taskDescription); updateAnalysisStatus(); } }
        };
    }

    private AnalysisConfiguration getCurrentAnalysisConfiguration() { if (!analysisModel.isAnalysisConfigured()) return null; return new AnalysisConfiguration( analysisModel.getExcelData(), analysisModel.getCurrentMode(), analysisModel.getSelectedTimestamp(), analysisModel.getIntervalStartTimestamp(), analysisModel.getIntervalEndTimestamp(), analysisModel.getOpticsEpsilon(), analysisModel.getOpticsMinPts(), analysisModel.getOpticsScalingType(), analysisModel.getDbscanEpsilon(), analysisModel.getDbscanMinPts(), analysisModel.getDbscanScalingType(), analysisModel.getSelectedXVariable(), analysisModel.getSelectedYVariable(), analysisModel.getXExtractor(), analysisModel.getYExtractor() ); }
    private void showDataDialog() { if (!analysisModel.isAnalysisDataAvailable()) { showInfoDialogOnEDT("Keine Analysedaten verfügbar."); return; } List<CalculatedDataPoint> dataToShow = analysisModel.getCurrentAnalysisData(); if (dataToShow == null || dataToShow.isEmpty()) { showInfoDialogOnEDT("Keine verarbeiteten Datenpunkte vorhanden."); return; } logger.debug("Showing general data table ({} points).", dataToShow.size()); if (tableDialog != null && tableDialog.isModuleInfoAvailable() != analysisModel.hasModuleInfo()) { tableDialog.dispose(); tableDialog = null; } if (tableDialog == null) { tableDialog = new TableDialog(mainView, analysisModel.hasModuleInfo()); } tableDialog.updateData(dataToShow); tableDialog.setVisible(true); tableDialog.toFront(); }
    
    private void showPlotDialog() { 
    	if (!analysisModel.isAnalysisDataAvailable()) { 
    		showInfoDialogOnEDT("Keine Analysedaten für Plot verfügbar."); 
    		return; 
    		} 
    	String xVarName = analysisModel.getSelectedXVariable(); 
    	String yVarName = analysisModel.getSelectedYVariable(); 
    	logger.debug("Showing cluster plot for selected variables: X='{}', Y='{}'", xVarName, yVarName); 
    	
    	if (plotDialog == null) {
    		plotDialog = new ClusterPlotDialog(mainView); 
    		} 
    	
    	// TEST: Dialog IMMER neu erstellen statt wiederverwenden
//        logger.debug("TEST: Forcing new ClusterPlotDialog instance creation.");
//        ClusterPlotDialog localPlotDialog = new ClusterPlotDialog(mainView); // Immer neu!
    	
    	// ---> TEST: Dialog OHNE Besitzer erstellen <---
//        logger.warn("TESTING: Creating dialog with NULL owner.");
//        ClusterPlotDialog localPlotDialog = new ClusterPlotDialog(null); // Owner ist null!
        // ------------------------------------------
    	
    	plotDialog.updatePlot( 
    			analysisModel.getCurrentAnalysisData(), 
    			analysisModel.getNumberOfClusters(), 
    			analysisModel.getModuleInfo(), 
    			xVarName, yVarName ); 
    	plotDialog.setVisible(true); 
    	plotDialog.toFront(); 
    	}
    
    
    private void showOutlierDialog() { if (!analysisModel.isAnalysisDataAvailable()) { showInfoDialogOnEDT("Keine Analysedaten für Ausreißer verfügbar."); return; } List<CalculatedDataPoint> outliers = analysisModel.getAllOutliers(); if (outliers.isEmpty()) { showInfoDialogOnEDT("Keine Ausreißer gefunden."); if (outlierDialog != null) outlierDialog.setVisible(false); return; } logger.debug("Showing outlier dialog."); if (outlierDialog == null || outlierDialog.isModuleInfoAvailable() != analysisModel.hasModuleInfo()) { if(outlierDialog != null) outlierDialog.dispose(); outlierDialog = new OutlierDialog(mainView, analysisModel.hasModuleInfo()); } outlierDialog.updateData(outliers); outlierDialog.setVisible(true); outlierDialog.toFront(); }
    private void showHierarchicalClusterView() { logger.debug("showHierarchicalClusterView triggered."); if (!analysisModel.isAnalysisDataAvailable()) { showInfoDialogOnEDT("Keine Analysedaten verfügbar."); return; } List<CalculatedDataPoint> dataToShow = analysisModel.getCurrentAnalysisData(); if (dataToShow == null || dataToShow.isEmpty()) { showInfoDialogOnEDT("Keine Datenpunkte für die Hierarchieansicht vorhanden."); return; } Map<String, List<CalculatedDataPoint>> hierarchy = groupTrackersByInverter(dataToShow); if (hierarchy.isEmpty()) { showInfoDialogOnEDT("Konnte Tracker nicht nach Wechselrichtern gruppieren (Namensformat prüfen: TR#?<X>.<Y>?)."); return; } logger.debug("Showing hierarchical view with {} inverters.", hierarchy.size()); if (hierarchyDialog == null) { hierarchyDialog = new HierarchicalClusterDialog(mainView); } hierarchyDialog.updateData(hierarchy); hierarchyDialog.setVisible(true); hierarchyDialog.toFront(); }
    private Map<String, List<CalculatedDataPoint>> groupTrackersByInverter(List<CalculatedDataPoint> trackers) { Map<String, List<CalculatedDataPoint>> grouped = new LinkedHashMap<>(); Pattern namePattern = Pattern.compile("^TR(?:#| )?(\\d+)\\.(\\d+)$", Pattern.CASE_INSENSITIVE); for (CalculatedDataPoint tracker : trackers) { if (tracker == null || tracker.getName() == null) continue; String trackerName = tracker.getName().trim(); Matcher matcher = namePattern.matcher(trackerName); String inverterKey = "Unbekannt"; if (matcher.matches()) { inverterKey = "WR" + matcher.group(1); } else { logger.trace("Could not parse inverter/tracker from name '{}'. Grouping as '{}'.", trackerName, inverterKey); } grouped.computeIfAbsent(inverterKey, k -> new ArrayList<>()).add(tracker); } for(List<CalculatedDataPoint> trackerList : grouped.values()) { trackerList.sort(Comparator.comparingInt(dp -> { Matcher m = namePattern.matcher(dp.getName().trim()); return m.matches() ? Integer.parseInt(m.group(2)) : Integer.MAX_VALUE; })); } return grouped; }
    private void updateTimestampList(String currentSingleSelection) { logger.debug("Updating timestamp lists UI. Target single selection: {}", currentSingleSelection); isUpdatingComboBox = true; try { List<String> ts = analysisModel.getTimestamps(); boolean hasTimestamps = (ts != null && !ts.isEmpty()); JComboBox<String> cbSingle = mainView.getTimestampComboBox(); Object prevSingle = cbSingle.getSelectedItem(); cbSingle.removeAllItems(); if (hasTimestamps) { ts.forEach(cbSingle::addItem); if (currentSingleSelection != null && ts.contains(currentSingleSelection)) { cbSingle.setSelectedItem(currentSingleSelection); } else if (prevSingle != null && ts.contains(prevSingle.toString())) { cbSingle.setSelectedItem(prevSingle); } else { cbSingle.setSelectedIndex(-1); } } else { cbSingle.setSelectedIndex(-1); } JComboBox<String> cbStart = mainView.getIntervalStartComboBox(); JComboBox<String> cbEnd = mainView.getIntervalEndComboBox(); Object prevStart = cbStart.getSelectedItem(); Object prevEnd = cbEnd.getSelectedItem(); cbStart.removeAllItems(); cbEnd.removeAllItems(); if (hasTimestamps) { ts.forEach(cbStart::addItem); ts.forEach(cbEnd::addItem); if (prevStart != null && ts.contains(prevStart.toString())) { cbStart.setSelectedItem(prevStart); } else { cbStart.setSelectedIndex(0); } if (prevEnd != null && ts.contains(prevEnd.toString())) { cbEnd.setSelectedItem(prevEnd); } else { cbEnd.setSelectedIndex(ts.size() - 1); } validateIntervalSelection(); } else { cbStart.setSelectedIndex(-1); cbEnd.setSelectedIndex(-1); } logger.debug("Timestamp lists updated. Size: {}", hasTimestamps ? ts.size() : 0); } catch (Exception e) { logger.error("Error updating timestamp UI.", e); } finally { isUpdatingComboBox = false; } }
    private boolean validateIntervalOrder(String startStr, String endStr) { if (startStr == null || endStr == null) return false; Date startDate = parseTimestamp(startStr); Date endDate = parseTimestamp(endStr); return startDate != null && endDate != null && !startDate.after(endDate); }
    private void validateIntervalSelection() { if (isUpdatingComboBox) return; JComboBox<String> cbStart = mainView.getIntervalStartComboBox(); JComboBox<String> cbEnd = mainView.getIntervalEndComboBox(); int startIndex = cbStart.getSelectedIndex(); int endIndex = cbEnd.getSelectedIndex(); if (startIndex != -1 && endIndex != -1 && startIndex > endIndex) { logger.debug("Adjusting interval end index ({}) to match start index ({}).", endIndex, startIndex); isUpdatingComboBox = true; cbEnd.setSelectedIndex(startIndex); isUpdatingComboBox = false; } }
    private void showErrorDialogOnEDT(String message) { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> showErrorDialogOnEDT(message)); return; } JOptionPane.showMessageDialog(mainView, message, "Fehler", JOptionPane.ERROR_MESSAGE); }
    private void showInfoDialogOnEDT(String message) { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> showInfoDialogOnEDT(message)); return; } JOptionPane.showMessageDialog(mainView, message, "Information", JOptionPane.INFORMATION_MESSAGE); }
    private void updateAnalysisStatus() { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(this::updateAnalysisStatus); return; } logger.debug("Updating analysis status UI..."); boolean dataLoaded = analysisModel.isDataLoaded(); boolean analysisConfigured = analysisModel.isAnalysisConfigured(); boolean analysisAvailable = analysisModel.isAnalysisDataAvailable(); boolean outliersExist = analysisAvailable && !analysisModel.getAllOutliers().isEmpty(); mainView.updateControlStates(dataLoaded, analysisModel.getCurrentMode()); if (analysisAvailable) { int clusters = analysisModel.getNumberOfClusters(); int outliers = analysisModel.getAllOutliers().size(); String targetDesc = (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP) ? "'" + analysisModel.getSelectedTimestamp() + "'" : "Intervall [...]"; mainView.setStatusLabel(String.format("Analyse %s: %d Cluster, %d Ausreißer (X:%s, Y:%s)", targetDesc, clusters, outliers, analysisModel.getSelectedXVariable(), analysisModel.getSelectedYVariable())); mainView.getShowTableButton().setEnabled(true); mainView.getShowPlotButton().setEnabled(true); mainView.getShowOutliersButton().setEnabled(outliersExist); mainView.getShowHierarchyButton().setEnabled(true); mainView.getExportExcelButton().setEnabled(true); mainView.getEstimateParamsButton().setEnabled(true); if (tableDialog != null && tableDialog.isVisible()) showDataDialog(); if (plotDialog != null && plotDialog.isVisible()) showPlotDialog(); if (outlierDialog != null && outlierDialog.isVisible()) { if (outliersExist) showOutlierDialog(); else { outlierDialog.setVisible(false); } } if (hierarchyDialog != null && hierarchyDialog.isVisible()) showHierarchicalClusterView(); } else { String status; if (!dataLoaded) { status = "Bereit. Excel-Datei laden."; } else if (!analysisConfigured) { status = (analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP) ? "Bitte Zeitstempel für Analyse auswählen." : "Bitte gültiges Zeitintervall für Analyse auswählen."; } else { status = "Bereit zur Analyse für " + ((analysisModel.getCurrentMode() == AnalysisMode.SINGLE_TIMESTAMP) ? "Zeitstempel '" + analysisModel.getSelectedTimestamp() + "'" : "Intervall"); } mainView.setStatusLabel(status); mainView.getShowTableButton().setEnabled(false); mainView.getShowPlotButton().setEnabled(false); mainView.getShowOutliersButton().setEnabled(false); mainView.getShowHierarchyButton().setEnabled(false); mainView.getExportExcelButton().setEnabled(false); mainView.getEstimateParamsButton().setEnabled(dataLoaded); if (tableDialog != null) { tableDialog.setVisible(false); tableDialog.dispose(); tableDialog = null; } if (plotDialog != null) { plotDialog.setVisible(false); plotDialog.dispose(); plotDialog = null; } if (outlierDialog != null) { outlierDialog.setVisible(false); outlierDialog.dispose(); outlierDialog = null; } if (hierarchyDialog != null) { hierarchyDialog.setVisible(false); hierarchyDialog.dispose(); hierarchyDialog = null; } } }
    @Override public void propertyChange(PropertyChangeEvent evt) { String propName = evt.getPropertyName(); if (!"progress".equals(propName)) { logger.debug("Controller received PropertyChangeEvent: Name='{}'", propName); } SwingUtilities.invokeLater(() -> { switch (propName) { case "excelData": boolean loaded = analysisModel.isDataLoaded(); updateTimestampList(null); mainView.updateControlStates(loaded, analysisModel.getCurrentMode()); updateAnalysisStatus(); if (!loaded) { /* Close dialogs */ if (tableDialog != null) { tableDialog.dispose(); tableDialog = null; } if (plotDialog != null) { plotDialog.dispose(); plotDialog = null; } if (outlierDialog != null) { outlierDialog.dispose(); outlierDialog = null; } if (hierarchyDialog != null) { hierarchyDialog.dispose(); hierarchyDialog = null; } } break; case "analysisMode": mainView.updateControlStates(analysisModel.isDataLoaded(), analysisModel.getCurrentMode()); updateAnalysisStatus(); break; case "selectedTimestamp": String newTs = (String) evt.getNewValue(); if (!Objects.equals(newTs, mainView.getTimestampComboBox().getSelectedItem())) { isUpdatingComboBox = true; mainView.getTimestampComboBox().setSelectedItem(newTs); isUpdatingComboBox = false; } updateAnalysisStatus(); break; case "intervalTimestamps": String[] interval = (String[]) evt.getNewValue(); if (interval != null && interval.length == 2) { isUpdatingComboBox = true; if (!Objects.equals(interval[0], mainView.getIntervalStartComboBox().getSelectedItem())) { mainView.getIntervalStartComboBox().setSelectedItem(interval[0]); } if (!Objects.equals(interval[1], mainView.getIntervalEndComboBox().getSelectedItem())) { mainView.getIntervalEndComboBox().setSelectedItem(interval[1]); } isUpdatingComboBox = false; validateIntervalSelection(); } updateAnalysisStatus(); break; case "analysisVariables": isUpdatingComboBox = true; try { if (!Objects.equals(analysisModel.getSelectedXVariable(), mainView.getXVariableComboBox().getSelectedItem())) mainView.getXVariableComboBox().setSelectedItem(analysisModel.getSelectedXVariable()); if (!Objects.equals(analysisModel.getSelectedYVariable(), mainView.getYVariableComboBox().getSelectedItem())) mainView.getYVariableComboBox().setSelectedItem(analysisModel.getSelectedYVariable()); } finally { isUpdatingComboBox = false; } break; case "analysisComplete": logger.info("Analysis complete signal received. Updating UI status."); updateAnalysisStatus(); break; case "analysisError": Throwable error = (evt.getNewValue() instanceof Throwable) ? (Throwable)evt.getNewValue() : null; String errorMsg = formatErrorMessage(error); logger.error("Analysis error signal received: {}", errorMsg, error); showErrorDialogOnEDT("Fehler bei der Analyse:\n" + errorMsg); mainView.setStatusLabel("Analyse fehlgeschlagen."); updateAnalysisStatus(); break; case "opticsParameters": case "dbscanParameters": case "opticsScalingType": case "dbscanScalingType": case "processedDataMap": case "processedDataList": case "clusteringResult": case "outlierDetectionComplete": logger.trace("Property change handled/ignored: {}", propName); break; default: if (!"progress".equals(propName)) logger.warn("Unhandled property change event in Controller: {}", propName); break; } }); }
    private String formatErrorMessage(Throwable throwable) { if (throwable == null) return "Unbekannter Fehler."; if (throwable instanceof InterruptedException) return "Vorgang abgebrochen."; if (throwable instanceof OutOfMemoryError) return "Nicht genügend Speicher!"; if (throwable instanceof IOException) return "Datei-Fehler: " + throwable.getMessage(); String msg = throwable.getMessage(); return (msg != null && !msg.trim().isEmpty()) ? msg : throwable.getClass().getSimpleName(); }
    private Date parseTimestamp(String timestampStr) { if (timestampStr == null) return null; try { synchronized (DATE_FORMAT_PARSER) { return DATE_FORMAT_PARSER.parse(timestampStr); } } catch (ParseException e) { logger.warn("Could not parse timestamp string for validation: {}", timestampStr); return null; } }
}