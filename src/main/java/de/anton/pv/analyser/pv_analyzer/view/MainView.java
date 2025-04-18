package de.anton.pv.analyser.pv_analyzer.view;

import de.anton.pv.analyser.pv_analyzer.model.ScalingType;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel;
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel.AnalysisMode; // Import Enum

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.EtchedBorder;
import java.awt.*;
import java.io.File;
import java.util.List;

/**
 * Main GUI View. Includes controls for loading, analysis mode selection (single timestamp
 * or interval max vector), parameters, variables, results, export, and parameter estimation.
 */
public class MainView extends JFrame {

    private File currentDirectory = new File("./PV-Anlagen");

    // --- UI Components ---
    private JButton btnLoadFile;
    private JFileChooser fileChooser;
    private JRadioButton rbSingleTimestamp;
    private JRadioButton rbInterval;
    private ButtonGroup modeGroup;
    private JLabel lblTimestampOrInterval;
    private JComboBox<String> cmbTimestamp;
    private JComboBox<String> cmbIntervalStart;
    private JLabel lblIntervalSeparator;
    private JComboBox<String> cmbIntervalEnd;
    private JPanel pnlTimestampSelection;
    private JComboBox<String> cmbXVariable;
    private JComboBox<String> cmbYVariable;
    private JTextField txtOpticsEpsilon;
    private JTextField txtOpticsMinPts;
    private JComboBox<ScalingType> cmbOpticsScaling;
    private JTextField txtDbscanEpsilon;
    private JTextField txtDbscanMinPts;
    private JComboBox<ScalingType> cmbDbscanScaling;
    private JButton btnApplyParams;
    private JButton btnEstimateParams;
    private JButton btnShowTable;
    private JButton btnShowPlot;
    private JButton btnShowOutliers;
    private JButton btnShowHierarchy;
    private JButton btnExportExcel;
    private JLabel lblStatus;
    private JPanel pnlMainControls;
    private final List<String> availableAnalysisVariables;

    public MainView(List<String> availableVariables) {
        this.availableAnalysisVariables = availableVariables != null ? availableVariables : List.of();
        initComponents();
        layoutComponents();
        setupWindow();
    }

    private void initComponents() {
        fileChooser = new JFileChooser(currentDirectory); fileChooser.setDialogTitle("Excel-Datei auswählen"); fileChooser.setFileSelectionMode(JFileChooser.FILES_ONLY); fileChooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Excel Dateien (*.xlsx, *.xls)", "xlsx", "xls"));
        btnLoadFile = new JButton("Excel laden...");
        rbSingleTimestamp = new JRadioButton("Einzelner Zeitstempel:", true); rbInterval = new JRadioButton("Intervall (Max Vektor):"); modeGroup = new ButtonGroup(); modeGroup.add(rbSingleTimestamp); modeGroup.add(rbInterval);
        lblTimestampOrInterval = new JLabel("Zeitstempel:"); cmbTimestamp = new JComboBox<>(); cmbTimestamp.setToolTipText("Wählen Sie den zu analysierenden Zeitstempel"); cmbIntervalStart = new JComboBox<>(); cmbIntervalStart.setToolTipText("Start-Zeitstempel des Intervalls"); lblIntervalSeparator = new JLabel(" bis "); cmbIntervalEnd = new JComboBox<>(); cmbIntervalEnd.setToolTipText("End-Zeitstempel des Intervalls"); Dimension timeComboSize = new Dimension(180, cmbTimestamp.getPreferredSize().height); cmbTimestamp.setPreferredSize(timeComboSize); cmbIntervalStart.setPreferredSize(timeComboSize); cmbIntervalEnd.setPreferredSize(timeComboSize); cmbIntervalStart.setVisible(false); lblIntervalSeparator.setVisible(false); cmbIntervalEnd.setVisible(false);
        cmbXVariable = new JComboBox<>(availableAnalysisVariables.toArray(new String[0])); cmbXVariable.setToolTipText("Variable für die X-Achse der Analyse"); cmbYVariable = new JComboBox<>(availableAnalysisVariables.toArray(new String[0])); cmbYVariable.setToolTipText("Variable für die Y-Achse der Analyse"); cmbXVariable.setSelectedItem(AnalysisModel.VAR_SPEZ_LEISTUNG); cmbYVariable.setSelectedItem(AnalysisModel.VAR_DC_SPANNUNG);
        txtOpticsEpsilon = new JTextField(6); txtOpticsEpsilon.setToolTipText("OPTICS Epsilon"); txtOpticsMinPts = new JTextField(4); txtOpticsMinPts.setToolTipText("OPTICS MinPts"); cmbOpticsScaling = new JComboBox<>(ScalingType.values()); cmbOpticsScaling.setToolTipText("OPTICS Skalierung"); txtDbscanEpsilon = new JTextField(6); txtDbscanEpsilon.setToolTipText("DBSCAN Epsilon"); txtDbscanMinPts = new JTextField(4); txtDbscanMinPts.setToolTipText("DBSCAN MinPts"); cmbDbscanScaling = new JComboBox<>(ScalingType.values()); cmbDbscanScaling.setToolTipText("DBSCAN Skalierung"); btnApplyParams = new JButton("Anwenden & Analysieren"); btnEstimateParams = new JButton("Parameter schätzen..."); btnEstimateParams.setToolTipText("Öffnet Diagramme zur Schätzung der Epsilon-Werte");
        btnShowTable = new JButton("Daten-Tabelle"); btnShowPlot = new JButton("Cluster-Plot"); btnShowOutliers = new JButton("Ausreißer-Liste"); btnShowHierarchy = new JButton("Hierarchie-Ansicht"); btnExportExcel = new JButton("Export Analyse...");
        lblStatus = new JLabel("Initialisierung...");
    }

    private void layoutComponents() {
        Container contentPane = getContentPane(); contentPane.setLayout(new BorderLayout(5, 5));
        JPanel pnlFile = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 5)); pnlFile.add(btnLoadFile); contentPane.add(pnlFile, BorderLayout.NORTH);
        pnlMainControls = new JPanel(); pnlMainControls.setLayout(new BoxLayout(pnlMainControls, BoxLayout.Y_AXIS)); pnlMainControls.setBorder(new EmptyBorder(5, 10, 5, 10));
        JPanel pnlSelection = new JPanel(new GridBagLayout()); pnlSelection.setBorder(BorderFactory.createTitledBorder("Analysekonfiguration")); GridBagConstraints gbcSel = new GridBagConstraints(); gbcSel.insets = new Insets(3, 5, 3, 5); gbcSel.anchor = GridBagConstraints.WEST;
        gbcSel.gridx = 0; gbcSel.gridy = 0; gbcSel.gridwidth = 1; gbcSel.fill = GridBagConstraints.NONE; gbcSel.weightx = 0; pnlSelection.add(rbSingleTimestamp, gbcSel); gbcSel.gridx = 1; pnlSelection.add(rbInterval, gbcSel); gbcSel.gridx = 2; gbcSel.gridwidth = 3; gbcSel.weightx = 1.0; pnlSelection.add(Box.createHorizontalGlue(), gbcSel); gbcSel.gridwidth = 1; gbcSel.weightx = 0;
        gbcSel.gridx = 0; gbcSel.gridy = 1; gbcSel.anchor = GridBagConstraints.EAST; pnlSelection.add(lblTimestampOrInterval, gbcSel); gbcSel.gridx = 1; gbcSel.gridwidth = 4; gbcSel.anchor = GridBagConstraints.WEST; gbcSel.fill = GridBagConstraints.HORIZONTAL; pnlTimestampSelection = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); pnlTimestampSelection.add(cmbTimestamp); pnlTimestampSelection.add(cmbIntervalStart); pnlTimestampSelection.add(lblIntervalSeparator); pnlTimestampSelection.add(cmbIntervalEnd); pnlSelection.add(pnlTimestampSelection, gbcSel); gbcSel.gridwidth = 1;
        gbcSel.gridx = 0; gbcSel.gridy = 2; gbcSel.anchor = GridBagConstraints.EAST; gbcSel.fill = GridBagConstraints.NONE; pnlSelection.add(new JLabel("X-Achse:"), gbcSel); gbcSel.gridx = 1; gbcSel.anchor = GridBagConstraints.WEST; gbcSel.fill = GridBagConstraints.HORIZONTAL; gbcSel.weightx = 0.5; pnlSelection.add(cmbXVariable, gbcSel); gbcSel.gridx = 2; gbcSel.anchor = GridBagConstraints.EAST; gbcSel.fill = GridBagConstraints.NONE; gbcSel.weightx = 0; gbcSel.insets = new Insets(3, 15, 3, 5); pnlSelection.add(new JLabel("Y-Achse:"), gbcSel); gbcSel.insets = new Insets(3, 5, 3, 5); gbcSel.gridx = 3; gbcSel.anchor = GridBagConstraints.WEST; gbcSel.fill = GridBagConstraints.HORIZONTAL; gbcSel.weightx = 0.5; pnlSelection.add(cmbYVariable, gbcSel); gbcSel.gridx = 4; pnlSelection.add(Box.createHorizontalStrut(1), gbcSel);
        pnlMainControls.add(pnlSelection); pnlMainControls.add(Box.createRigidArea(new Dimension(0, 5)));
        JPanel pnlParameters = new JPanel(new GridBagLayout()); pnlParameters.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(EtchedBorder.LOWERED), "Algorithmus-Parameter")); GridBagConstraints gbcParam = new GridBagConstraints(); gbcParam.insets = new Insets(4, 6, 4, 6); gbcParam.anchor = GridBagConstraints.WEST;
        gbcParam.gridx = 0; gbcParam.gridy = 0; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; pnlParameters.add(new JLabel("OPTICS ε:"), gbcParam); gbcParam.gridx = 1; gbcParam.weightx = 0.4; gbcParam.fill = GridBagConstraints.HORIZONTAL; pnlParameters.add(txtOpticsEpsilon, gbcParam); gbcParam.gridx = 2; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; gbcParam.insets = new Insets(4, 15, 4, 6); pnlParameters.add(new JLabel("MinPts:"), gbcParam); gbcParam.gridx = 3; gbcParam.weightx = 0.4; gbcParam.fill = GridBagConstraints.HORIZONTAL; gbcParam.insets = new Insets(4, 6, 4, 6); pnlParameters.add(txtOpticsMinPts, gbcParam); gbcParam.gridx = 4; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; gbcParam.insets = new Insets(4, 15, 4, 6); pnlParameters.add(new JLabel("Scaling:"), gbcParam); gbcParam.gridx = 5; gbcParam.weightx = 0.2; gbcParam.fill = GridBagConstraints.HORIZONTAL; gbcParam.insets = new Insets(4, 6, 4, 6); pnlParameters.add(cmbOpticsScaling, gbcParam);
        gbcParam.gridx = 0; gbcParam.gridy = 1; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; pnlParameters.add(new JLabel("DBSCAN ε:"), gbcParam); gbcParam.gridx = 1; gbcParam.weightx = 0.4; gbcParam.fill = GridBagConstraints.HORIZONTAL; pnlParameters.add(txtDbscanEpsilon, gbcParam); gbcParam.gridx = 2; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; gbcParam.insets = new Insets(4, 15, 4, 6); pnlParameters.add(new JLabel("MinPts:"), gbcParam); gbcParam.gridx = 3; gbcParam.weightx = 0.4; gbcParam.fill = GridBagConstraints.HORIZONTAL; gbcParam.insets = new Insets(4, 6, 4, 6); pnlParameters.add(txtDbscanMinPts, gbcParam); gbcParam.gridx = 4; gbcParam.weightx = 0; gbcParam.fill = GridBagConstraints.NONE; gbcParam.insets = new Insets(4, 15, 4, 6); pnlParameters.add(new JLabel("Scaling:"), gbcParam); gbcParam.gridx = 5; gbcParam.weightx = 0.2; gbcParam.fill = GridBagConstraints.HORIZONTAL; gbcParam.insets = new Insets(4, 6, 4, 6); pnlParameters.add(cmbDbscanScaling, gbcParam);
        JPanel paramButtonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 20, 0)); paramButtonPanel.add(btnEstimateParams); paramButtonPanel.add(btnApplyParams); gbcParam.gridx = 0; gbcParam.gridy = 2; gbcParam.gridwidth = 6; gbcParam.fill = GridBagConstraints.NONE; gbcParam.anchor = GridBagConstraints.CENTER; gbcParam.insets = new Insets(10, 5, 5, 5); pnlParameters.add(paramButtonPanel, gbcParam);
        pnlMainControls.add(pnlParameters); pnlMainControls.add(Box.createRigidArea(new Dimension(0, 5)));
        JPanel pnlResultsButtons = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5)); pnlResultsButtons.setBorder(BorderFactory.createTitledBorder(BorderFactory.createEtchedBorder(EtchedBorder.LOWERED), "Ergebnisse")); pnlResultsButtons.add(btnShowTable); pnlResultsButtons.add(btnShowPlot); pnlResultsButtons.add(btnShowOutliers); pnlResultsButtons.add(btnShowHierarchy); pnlResultsButtons.add(Box.createHorizontalStrut(15)); pnlResultsButtons.add(btnExportExcel); pnlResultsButtons.setMaximumSize(new Dimension(Short.MAX_VALUE, pnlResultsButtons.getPreferredSize().height)); pnlMainControls.add(pnlResultsButtons);
        contentPane.add(pnlMainControls, BorderLayout.CENTER);
        JPanel pnlStatus = new JPanel(new FlowLayout(FlowLayout.LEFT)); pnlStatus.setBorder(BorderFactory.createCompoundBorder( BorderFactory.createEtchedBorder(EtchedBorder.LOWERED), new EmptyBorder(3, 5, 3, 5))); pnlStatus.add(lblStatus); contentPane.add(pnlStatus, BorderLayout.SOUTH);
    
    }

    private void setupWindow() { setTitle("PV Analyzer v0.8 (Interval Mode)"); setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); setMinimumSize(new Dimension(850, 520)); pack(); setLocationRelativeTo(null); }
    public void updateControlStates(boolean dataLoaded, AnalysisMode currentMode) { boolean enableBasic = dataLoaded; cmbTimestamp.setEnabled(enableBasic); cmbIntervalStart.setEnabled(enableBasic); cmbIntervalEnd.setEnabled(enableBasic); cmbXVariable.setEnabled(enableBasic); cmbYVariable.setEnabled(enableBasic); txtOpticsEpsilon.setEnabled(enableBasic); txtOpticsMinPts.setEnabled(enableBasic); cmbOpticsScaling.setEnabled(enableBasic); txtDbscanEpsilon.setEnabled(enableBasic); txtDbscanMinPts.setEnabled(enableBasic); cmbDbscanScaling.setEnabled(enableBasic); btnApplyParams.setEnabled(enableBasic); btnEstimateParams.setEnabled(enableBasic); boolean isSingleMode = (currentMode == AnalysisMode.SINGLE_TIMESTAMP); lblTimestampOrInterval.setText(isSingleMode ? "Zeitstempel:" : "Intervall:"); cmbTimestamp.setVisible(isSingleMode); cmbIntervalStart.setVisible(!isSingleMode); lblIntervalSeparator.setVisible(!isSingleMode); cmbIntervalEnd.setVisible(!isSingleMode); if (!dataLoaded) { btnShowTable.setEnabled(false); btnShowPlot.setEnabled(false); btnShowOutliers.setEnabled(false); btnShowHierarchy.setEnabled(false); btnExportExcel.setEnabled(false); btnEstimateParams.setEnabled(false); } if (pnlTimestampSelection != null) { pnlTimestampSelection.revalidate(); pnlTimestampSelection.repaint(); } }
    public void setBusyState(boolean busy) { setCursor(busy ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor()); setEnabledRecursive(pnlMainControls, !busy); btnLoadFile.setEnabled(!busy); Component topPanel = getContentPane().getComponent(0); if (topPanel != null) { setEnabledRecursive(topPanel, !busy); } }
    private void setEnabledRecursive(Component component, boolean enabled) { if (!(component instanceof JLabel)) { component.setEnabled(enabled); } if (component instanceof Container) { for (Component child : ((Container) component).getComponents()) { if (child instanceof JScrollPane) { JScrollPane scrollPane = (JScrollPane) child; Component view = scrollPane.getViewport().getView(); if (view != null) setEnabledRecursive(view, enabled); } else { setEnabledRecursive(child, enabled); } } } }
    public void setStatusLabel(String text) { if (!SwingUtilities.isEventDispatchThread()) { SwingUtilities.invokeLater(() -> lblStatus.setText(text != null ? text : "")); } else { lblStatus.setText(text != null ? text : ""); } }
    public JButton getLoadFileButton() { return btnLoadFile; } public JFileChooser getFileChooser() { return fileChooser; } public JRadioButton getSingleTimestampRadioButton() { return rbSingleTimestamp; } public JRadioButton getIntervalRadioButton() { return rbInterval; } public JComboBox<String> getTimestampComboBox() { return cmbTimestamp; } public JComboBox<String> getIntervalStartComboBox() { return cmbIntervalStart; } public JComboBox<String> getIntervalEndComboBox() { return cmbIntervalEnd; } public JComboBox<String> getXVariableComboBox() { return cmbXVariable; } public JComboBox<String> getYVariableComboBox() { return cmbYVariable; } public JTextField getOpticsEpsilonTextField() { return txtOpticsEpsilon; } public JTextField getOpticsMinPtsTextField() { return txtOpticsMinPts; } public JComboBox<ScalingType> getOpticsScalingComboBox() { return cmbOpticsScaling; } public JTextField getDbscanEpsilonTextField() { return txtDbscanEpsilon; } public JTextField getDbscanMinPtsTextField() { return txtDbscanMinPts; } public JComboBox<ScalingType> getDbscanScalingComboBox() { return cmbDbscanScaling; } public JButton getApplyParamsButton() { return btnApplyParams; } public JButton getEstimateParamsButton() { return btnEstimateParams; } public JButton getShowTableButton() { return btnShowTable; } public JButton getShowPlotButton() { return btnShowPlot; } public JButton getShowOutliersButton() { return btnShowOutliers; } public JButton getShowHierarchyButton() { return btnShowHierarchy; } public JButton getExportExcelButton() { return btnExportExcel; }

}