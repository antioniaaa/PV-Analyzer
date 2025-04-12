package de.anton.pv.analyser.pv_analyzer.view;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Dialog to display the hierarchical cluster/outlier view.
 * Includes buttons to reset the view and export it as an image.
 */
public class HierarchicalClusterDialog extends JDialog {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalClusterDialog.class);

    private final HierarchicalClusterPanel drawingPanel;
    private final JScrollPane scrollPane;
    private final JButton btnExportImage;

    private static final double EXPORT_SCALE = 3.0;
    private static final String EXPORT_FORMAT = "png";

    public HierarchicalClusterDialog(Frame owner) {
        super(owner, "Hierarchische Cluster/Ausreißer-Ansicht", false);
        drawingPanel = new HierarchicalClusterPanel();
        setLayout(new BorderLayout(5, 5));
        JButton resetButton = new JButton("Ansicht zurücksetzen"); resetButton.setToolTipText("Setzt Zoom und Position zurück"); resetButton.addActionListener(e -> drawingPanel.resetView());
        btnExportImage = new JButton("Als Bild exportieren..."); btnExportImage.setToolTipText("Speichert die aktuelle Ansicht als hochauflösendes PNG"); btnExportImage.addActionListener(e -> exportViewAsImage()); btnExportImage.setEnabled(false);
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5)); buttonPanel.add(resetButton); buttonPanel.add(btnExportImage); add(buttonPanel, BorderLayout.SOUTH);
        scrollPane = new JScrollPane(drawingPanel); scrollPane.setBorder(BorderFactory.createEtchedBorder()); scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED); scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED); scrollPane.getVerticalScrollBar().setUnitIncrement(16); scrollPane.getHorizontalScrollBar().setUnitIncrement(16); add(scrollPane, BorderLayout.CENTER);
        setSize(900, 600); setMinimumSize(new Dimension(450, 350)); setLocationByPlatform(true);
    }

    public void updateData(Map<String, List<CalculatedDataPoint>> hierarchyData) { boolean hasData = hierarchyData != null && !hierarchyData.isEmpty(); drawingPanel.setData(hierarchyData); btnExportImage.setEnabled(hasData); SwingUtilities.invokeLater(() -> { scrollPane.revalidate(); scrollPane.repaint(); }); }

    private void exportViewAsImage() {
        logger.debug("Export image action triggered."); setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR)); BufferedImage image = null;
        try { image = drawingPanel.createHighResImage(EXPORT_SCALE); } catch (Exception ex) { logger.error("Error during high-res image creation", ex); JOptionPane.showMessageDialog(this, "Fehler beim Erzeugen des Bildes:\n" + ex.getMessage(), "Export Fehler", JOptionPane.ERROR_MESSAGE); setCursor(Cursor.getDefaultCursor()); return; } finally { setCursor(Cursor.getDefaultCursor()); }
        if (image == null) { logger.warn("Export failed: createHighResImage returned null."); JOptionPane.showMessageDialog(this, "Bild konnte nicht erstellt werden (keine Daten?).", "Export Fehler", JOptionPane.WARNING_MESSAGE); return; }
        JFileChooser fileChooser = new JFileChooser(); fileChooser.setDialogTitle("Hierarchie als Bild speichern"); fileChooser.setSelectedFile(new File("PV_Hierarchie_Export." + EXPORT_FORMAT)); fileChooser.setFileFilter(new FileNameExtensionFilter("PNG Bilder (*.png)", "png")); fileChooser.setAcceptAllFileFilterUsed(false);
        int userSelection = fileChooser.showSaveDialog(this);
        if (userSelection == JFileChooser.APPROVE_OPTION) {
            File fileToSave = fileChooser.getSelectedFile(); if (!fileToSave.getName().toLowerCase().endsWith("." + EXPORT_FORMAT)) { fileToSave = new File(fileToSave.getParentFile(), fileToSave.getName() + "." + EXPORT_FORMAT); } logger.info("Attempting to save image to: {}", fileToSave.getAbsolutePath());
            if (fileToSave.exists()) { int response = JOptionPane.showConfirmDialog(this, "Die Datei '" + fileToSave.getName() + "' existiert bereits.\nÜberschreiben?", "Bestätigung", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE); if (response != JOptionPane.YES_OPTION) { logger.debug("Export cancelled by user (overwrite)."); return; } }
            try { boolean success = ImageIO.write(image, EXPORT_FORMAT, fileToSave); if (success) { logger.info("Image successfully saved."); JOptionPane.showMessageDialog(this, "Bild erfolgreich gespeichert:\n" + fileToSave.getAbsolutePath(), "Export erfolgreich", JOptionPane.INFORMATION_MESSAGE); } else { logger.error("Image export failed: ImageIO.write returned false."); JOptionPane.showMessageDialog(this, "Bild konnte nicht gespeichert werden (Format '" + EXPORT_FORMAT + "' nicht unterstützt?).", "Export Fehler", JOptionPane.ERROR_MESSAGE); } }
            catch (IOException ex) { logger.error("IOException during image save to {}", fileToSave.getAbsolutePath(), ex); JOptionPane.showMessageDialog(this, "Fehler beim Speichern des Bildes:\n" + ex.getMessage(), "Export Fehler", JOptionPane.ERROR_MESSAGE); }
            catch (Exception ex) { logger.error("Unexpected error during image save to {}", fileToSave.getAbsolutePath(), ex); JOptionPane.showMessageDialog(this, "Unerwarteter Fehler beim Speichern:\n" + ex.getMessage(), "Export Fehler", JOptionPane.ERROR_MESSAGE); }
        } else { logger.debug("Export cancelled by user (file chooser)."); }
    }
}