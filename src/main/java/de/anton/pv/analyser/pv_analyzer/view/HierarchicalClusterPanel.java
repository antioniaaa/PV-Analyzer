package de.anton.pv.analyser.pv_analyzer.view;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
// Removed unused Point2D import if direct axis panning is not used
// import java.awt.geom.Point2D;

/**
 * Panel for drawing the hierarchical view of inverters and trackers,
 * showing cluster ID, orientation, source timestamp, outlier, and performance status.
 * Supports panning (left-drag) and zooming (wheel).
 * Provides method to export the view as a high-resolution image.
 * Arranges inverters in rows.
 */
public class HierarchicalClusterPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(HierarchicalClusterPanel.class);

    // --- Data ---
    private Map<String, List<CalculatedDataPoint>> hierarchyData = new LinkedHashMap<>();

    // --- Drawing Constants ---
    private static final int INVERTERS_PER_ROW = 5;
    private static final int INVERTER_START_Y = 50;
    private static final int INVERTER_ROW_SPACING = 265;
    private static final int TRACKER_Y_OFFSET = 70;
    private static final int INVERTER_WIDTH = 100; private static final int INVERTER_HEIGHT = 35;
    private static final int TRACKER_DIAMETER = 30;
    private static final int LABEL_Y_OFFSET = 5; private static final int LABEL_LINE_HEIGHT = 11;
    private static final int HORIZONTAL_GROUP_SPACING = 50; private static final int HORIZONTAL_TRACKER_SPACING = 60;
    private static final int MIN_INVERTER_GROUP_WIDTH = INVERTER_WIDTH + 20;
    private static final Font BASE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 9); private static final Font INVERTER_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 10);
    private static final Color DEFAULT_COLOR = Color.DARK_GRAY;
    private static final Color NORMAL_TRACKER_FILL = new Color(200, 225, 250); private static final Color NORMAL_TRACKER_BORDER = Color.BLUE;
    private static final Color OUTLIER_LOW_PERF_FILL = new Color(255, 180, 180); private static final Color OUTLIER_LOW_PERF_BORDER = Color.RED;
    private static final Color OUTLIER_HIGH_PERF_FILL = new Color(180, 255, 180); private static final Color OUTLIER_HIGH_PERF_BORDER = new Color(0, 128, 0);
    private static final Color OUTLIER_MEDIAN_PERF_FILL = new Color(255, 220, 160); private static final Color OUTLIER_MEDIAN_PERF_BORDER = new Color(200, 120, 0);
    private static final Color INVERTER_OUTLIER_BORDER_COLOR = Color.RED;
    private static final Color LINE_COLOR_NORMAL = Color.GRAY; private static final Color LINE_COLOR_OUTLIER_LOW = new Color(255, 150, 150); private static final Color LINE_COLOR_OUTLIER_HIGH = new Color(130, 220, 130); private static final Color LINE_COLOR_OUTLIER_MEDIAN = new Color(255, 180, 100);
    private static final BasicStroke LINE_STROKE = new BasicStroke(1.0f); private static final BasicStroke INVERTER_BORDER_STROKE = new BasicStroke(1.0f); private static final BasicStroke INVERTER_OUTLIER_BORDER_STROKE = new BasicStroke(2.0f); private static final BasicStroke TRACKER_OUTLIER_BORDER_STROKE = new BasicStroke(1.5f);
    private static final Color CLUSTER_LABEL_COLOR = new Color(0, 0, 150); private static final Color OUTLIER_LABEL_COLOR = Color.RED; private static final Color TIMESTAMP_LABEL_COLOR = Color.GRAY;
    private static final Color PERF_HIGH_LABEL_COLOR = new Color(0, 128, 0); private static final Color PERF_LOW_LABEL_COLOR = new Color(180, 0, 0);

    // --- Interaction State ---
    private double scale = 1.0;
    private double offsetX = 0;
    private double offsetY = 0;
    // *** panLastPoint IST HIER DEFINIERT ***
    private Point panLastPoint = null;
    private Dimension preferredPanelSize = new Dimension(800, 400);

    public HierarchicalClusterPanel() {
        this.setBackground(Color.WHITE);
        InteractionHandler handler = new InteractionHandler();
        this.addMouseListener(handler);
        this.addMouseMotionListener(handler);
        this.addMouseWheelListener(handler);
    }

    public void setData(Map<String, List<CalculatedDataPoint>> hierarchy) { 
    	this.hierarchyData = hierarchy != null ? new LinkedHashMap<>(hierarchy) : new LinkedHashMap<>(); 
    	logger.debug("Hierarchy data updated with {} inverters.", this.hierarchyData.size()); 
    	calculatePreferredSize(); resetView(); repaint(); }
    
    
    public void resetView() { logger.trace("Resetting view"); scale = 1.0; offsetX = 0; offsetY = 0; revalidate(); repaint(); }
    @Override public Dimension getPreferredSize() { return preferredPanelSize; }

    private void calculatePreferredSize() {
        if (hierarchyData.isEmpty()) { preferredPanelSize = new Dimension(300, 200); revalidate(); return; }
        
        int numInverters = hierarchyData.size(); 
        int numRows = (int) Math.ceil((double) numInverters / INVERTERS_PER_ROW); 
        int numCols = Math.min(numInverters, INVERTERS_PER_ROW);
       
        int maxGroupWidth = 0; for (List<CalculatedDataPoint> trackers : hierarchyData.values()) { int trackersWidth = 0; if (trackers != null && !trackers.isEmpty()) { trackersWidth = trackers.size() * TRACKER_DIAMETER + Math.max(0, trackers.size() - 1) * HORIZONTAL_TRACKER_SPACING; } maxGroupWidth = Math.max(maxGroupWidth, Math.max(MIN_INVERTER_GROUP_WIDTH, trackersWidth)); }
        int totalWidth = numCols * maxGroupWidth + (numCols + 1) * HORIZONTAL_GROUP_SPACING;
        int labelSpace = 6 * LABEL_LINE_HEIGHT + LABEL_Y_OFFSET; int totalHeight = INVERTER_START_Y + numRows * INVERTER_ROW_SPACING + labelSpace + 60;
        preferredPanelSize = new Dimension(Math.max(600, totalWidth), Math.max(400, totalHeight));
        logger.trace("Calculated preferred size: {}", preferredPanelSize);
        revalidate(); // Trigger layout update in parent scroll pane
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); Graphics2D g2d = (Graphics2D) g.create(); setupGraphics(g2d);
        // Apply pan and zoom TRANSFORMATION
        g2d.translate(offsetX, offsetY);
        g2d.scale(scale, scale);
        try { drawHierarchy(g2d); } // Draw content with applied transform
        catch (Exception e) { logger.error("Error during screen hierarchy drawing", e); g2d.setColor(Color.RED); g2d.drawString("Error drawing: " + e.getMessage(), 20, 20); }
        finally { g2d.dispose(); }
    }

    private void setupGraphics(Graphics2D g2d) { g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON); g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON); g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY); g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE); }

    private void drawHierarchy(Graphics2D g2d) {
        if (hierarchyData.isEmpty()) { g2d.setColor(Color.GRAY); g2d.setFont(INVERTER_FONT); g2d.drawString("Keine Daten zum Anzeigen.", 20, 40); return; }
        int currentX = HORIZONTAL_GROUP_SPACING; int currentY = INVERTER_START_Y; int inverterCountInRow = 0;
        int maxGroupWidth = 0; for (List<CalculatedDataPoint> trackers : hierarchyData.values()) { int trackersWidth = 0; if (trackers != null && !trackers.isEmpty()) { trackersWidth = trackers.size() * TRACKER_DIAMETER + Math.max(0, trackers.size() - 1) * HORIZONTAL_TRACKER_SPACING; } maxGroupWidth = Math.max(maxGroupWidth, Math.max(MIN_INVERTER_GROUP_WIDTH, trackersWidth)); }
        g2d.setFont(BASE_FONT); FontMetrics fmLabel = g2d.getFontMetrics(); int labelAscent = fmLabel.getAscent();

        for (Map.Entry<String, List<CalculatedDataPoint>> inverterEntry : hierarchyData.entrySet()) {
            String inverterName = inverterEntry.getKey(); List<CalculatedDataPoint> trackers = inverterEntry.getValue() != null ? inverterEntry.getValue() : Collections.emptyList();
            int currentGroupWidth = 0; if (!trackers.isEmpty()) { currentGroupWidth = trackers.size() * TRACKER_DIAMETER + Math.max(0, trackers.size() - 1) * HORIZONTAL_TRACKER_SPACING; } currentGroupWidth = Math.max(MIN_INVERTER_GROUP_WIDTH, currentGroupWidth);
            int startXForGroup = currentX + (maxGroupWidth - currentGroupWidth) / 2;
            int inverterRectX = startXForGroup + (currentGroupWidth - INVERTER_WIDTH) / 2; int inverterRectY = currentY; int inverterBottomY = inverterRectY + INVERTER_HEIGHT; int trackerCenterY = inverterBottomY + TRACKER_Y_OFFSET; int trackerTopY = trackerCenterY - TRACKER_DIAMETER / 2;
            boolean inverterHasOutlier = trackers.stream().anyMatch(CalculatedDataPoint::isOutlier); g2d.setColor(Color.LIGHT_GRAY); g2d.fillRect(inverterRectX, inverterRectY, INVERTER_WIDTH, INVERTER_HEIGHT); g2d.setStroke(inverterHasOutlier ? INVERTER_OUTLIER_BORDER_STROKE : INVERTER_BORDER_STROKE); g2d.setColor(inverterHasOutlier ? INVERTER_OUTLIER_BORDER_COLOR : DEFAULT_COLOR); g2d.drawRect(inverterRectX, inverterRectY, INVERTER_WIDTH, INVERTER_HEIGHT); g2d.setStroke(LINE_STROKE);
            g2d.setFont(INVERTER_FONT); g2d.setColor(DEFAULT_COLOR); FontMetrics fmInverter = g2d.getFontMetrics(); int labelInverterWidth = fmInverter.stringWidth(inverterName); g2d.drawString(inverterName, inverterRectX + (INVERTER_WIDTH - labelInverterWidth) / 2, inverterRectY + fmInverter.getAscent() + (INVERTER_HEIGHT - fmInverter.getHeight()) / 2);
            int trackerStartX = startXForGroup + (currentGroupWidth - (trackers.size() * TRACKER_DIAMETER + Math.max(0, trackers.size() - 1) * HORIZONTAL_TRACKER_SPACING)) / 2;

            for (int i = 0; i < trackers.size(); i++) {
                CalculatedDataPoint tracker = trackers.get(i); boolean isOutlier = tracker.isOutlier(); String perfLabel = tracker.getPerformanceLabel();
                int trackerCenterX = trackerStartX + i * (TRACKER_DIAMETER + HORIZONTAL_TRACKER_SPACING) + TRACKER_DIAMETER / 2; int trackerLeftX = trackerCenterX - TRACKER_DIAMETER / 2;

                Color lineColor = LINE_COLOR_NORMAL; Color trackerFill = NORMAL_TRACKER_FILL; Color trackerBorder = NORMAL_TRACKER_BORDER; Stroke trackerStroke = LINE_STROKE;
                if (isOutlier) {
                    trackerStroke = TRACKER_OUTLIER_BORDER_STROKE;
                    if ("hoch".equals(perfLabel)) { lineColor = LINE_COLOR_OUTLIER_HIGH; trackerFill = OUTLIER_HIGH_PERF_FILL; trackerBorder = OUTLIER_HIGH_PERF_BORDER; }
                    else if ("niedrig".equals(perfLabel)) { lineColor = LINE_COLOR_OUTLIER_LOW; trackerFill = OUTLIER_LOW_PERF_FILL; trackerBorder = OUTLIER_LOW_PERF_BORDER; }
                    else { lineColor = LINE_COLOR_OUTLIER_MEDIAN; trackerFill = OUTLIER_MEDIAN_PERF_FILL; trackerBorder = OUTLIER_MEDIAN_PERF_BORDER; }
                }

                g2d.setColor(lineColor); g2d.drawLine(inverterRectX + INVERTER_WIDTH / 2, inverterBottomY, trackerCenterX, trackerTopY);
                g2d.setColor(trackerFill); g2d.fillOval(trackerLeftX, trackerTopY, TRACKER_DIAMETER, TRACKER_DIAMETER);
                g2d.setColor(trackerBorder); g2d.setStroke(trackerStroke); g2d.drawOval(trackerLeftX, trackerTopY, TRACKER_DIAMETER, TRACKER_DIAMETER); g2d.setStroke(LINE_STROKE);

                g2d.setFont(BASE_FONT); int labelY = trackerCenterY + TRACKER_DIAMETER / 2 + LABEL_Y_OFFSET + labelAscent; int labelWidth;
                String trackerId = parseTrackerIdFromName(tracker.getName()); g2d.setColor(DEFAULT_COLOR); labelWidth = fmLabel.stringWidth(trackerId); g2d.drawString(trackerId, trackerCenterX - labelWidth / 2, labelY); labelY += LABEL_LINE_HEIGHT;
                String orientation = tracker.getAusrichtung() != null ? tracker.getAusrichtung() : "?"; g2d.setColor(DEFAULT_COLOR); labelWidth = fmLabel.stringWidth(orientation); g2d.drawString(orientation, trackerCenterX - labelWidth / 2, labelY); labelY += LABEL_LINE_HEIGHT;
                int clusterId = tracker.getClusterGroup(); String clusterText = (clusterId == MyOPTICS.NOISE) ? "Noise" : "C:" + clusterId; g2d.setColor(CLUSTER_LABEL_COLOR); labelWidth = fmLabel.stringWidth(clusterText); g2d.drawString(clusterText, trackerCenterX - labelWidth / 2, labelY); labelY += LABEL_LINE_HEIGHT;
                String timestamp = tracker.getSourceTimestamp() != null ? tracker.getSourceTimestamp() : "N/A"; if (timestamp.length() > 16) { int spaceIdx = timestamp.indexOf(' '); if (spaceIdx > 0) timestamp = timestamp.substring(spaceIdx + 1); } g2d.setColor(TIMESTAMP_LABEL_COLOR); labelWidth = fmLabel.stringWidth(timestamp); g2d.drawString(timestamp, trackerCenterX - labelWidth / 2, labelY); labelY += LABEL_LINE_HEIGHT;
                String perfText = ""; Color perfColor = DEFAULT_COLOR; if (perfLabel != null && !perfLabel.isEmpty()) { if ("hoch".equals(perfLabel)) { perfText = "(Leistung Hoch)"; perfColor = PERF_HIGH_LABEL_COLOR; } else if ("niedrig".equals(perfLabel)) { perfText = "(Leistung Niedrig)"; perfColor = PERF_LOW_LABEL_COLOR; } else if ("median".equals(perfLabel)) { perfText = "(Leistung Median)"; } else { perfText = "(" + perfLabel + ")"; } g2d.setColor(perfColor); labelWidth = fmLabel.stringWidth(perfText); g2d.drawString(perfText, trackerCenterX - labelWidth / 2, labelY); labelY += LABEL_LINE_HEIGHT; }
                if (isOutlier) { String outlierText = "(Ausreißer)"; g2d.setColor(OUTLIER_LABEL_COLOR); labelWidth = fmLabel.stringWidth(outlierText); g2d.drawString(outlierText, trackerCenterX - labelWidth / 2, labelY); }
            }
            inverterCountInRow++; if (inverterCountInRow >= INVERTERS_PER_ROW) { currentX = HORIZONTAL_GROUP_SPACING; currentY += INVERTER_ROW_SPACING; inverterCountInRow = 0; } else { currentX += maxGroupWidth + HORIZONTAL_GROUP_SPACING; }
        }
    }

    /** Creates a high-resolution image of the current hierarchy drawing. */
    public BufferedImage createHighResImage(double exportScale) { if (hierarchyData.isEmpty()) { logger.warn("Cannot create image: Hierarchy data is empty."); return null; } Dimension logicalSize = this.preferredPanelSize; if (logicalSize.width <= 0 || logicalSize.height <= 0) { logger.warn("Cannot create image: Calculated preferred size is invalid ({}x{}).", logicalSize.width, logicalSize.height); return null; } int imageWidth = (int) Math.ceil(logicalSize.width * exportScale); int imageHeight = (int) Math.ceil(logicalSize.height * exportScale); logger.info("Creating export image with dimensions {}x{} (Scale: {}x)", imageWidth, imageHeight, exportScale); BufferedImage image = new BufferedImage(imageWidth, imageHeight, BufferedImage.TYPE_INT_ARGB); Graphics2D g2dImage = image.createGraphics(); try { setupGraphics(g2dImage); g2dImage.setColor(this.getBackground()); g2dImage.fillRect(0, 0, imageWidth, imageHeight); g2dImage.scale(exportScale, exportScale); drawHierarchy(g2dImage); } catch (Exception e) { logger.error("Error creating high-resolution image", e); g2dImage.setColor(Color.RED); g2dImage.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16)); g2dImage.drawString("Error creating image: " + e.getMessage(), 50, 50); } finally { g2dImage.dispose(); } return image; }
    private String parseTrackerIdFromName(String fullName) { if (fullName == null) return "?"; Pattern namePattern = Pattern.compile("^TR(?:#| )?(\\d+)\\.(\\d+)$", Pattern.CASE_INSENSITIVE); Matcher matcher = namePattern.matcher(fullName.trim()); if (matcher.matches()) { return "TR." + matcher.group(2); } int lastSeparator = Math.max(fullName.lastIndexOf('_'), fullName.lastIndexOf('.')); if (lastSeparator != -1 && lastSeparator < fullName.length() - 1) { return fullName.substring(lastSeparator + 1); } return fullName; }

    /** Handles mouse interactions for panning and zooming. */
    private class InteractionHandler extends MouseAdapter {
        @Override public void mousePressed(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) { // Use left button for panning
                panLastPoint = e.getPoint(); // Use instance variable
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                requestFocusInWindow(); // For wheel listener
            }
        }
        @Override public void mouseReleased(MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                panLastPoint = null; // Use instance variable
                setCursor(Cursor.getDefaultCursor());
            }
        }
        @Override public void mouseDragged(MouseEvent e) {
            if (panLastPoint != null && SwingUtilities.isLeftMouseButton(e)) { // Use instance variable
                int dx = e.getX() - panLastPoint.x;
                int dy = e.getY() - panLastPoint.y;
                // Apply delta to panel's offset
                offsetX += dx;
                offsetY += dy;
                panLastPoint = e.getPoint(); // Update last point
                repaint(); // Redraw with new offset
            }
        }
        @Override public void mouseWheelMoved(MouseWheelEvent e) {
            double rotation = e.getPreciseWheelRotation(); double scaleFactor = Math.pow(1.1, -rotation); double oldScale = scale; scale *= scaleFactor; scale = Math.max(0.05, Math.min(scale, 5.0));
            if (Math.abs(scale - oldScale) < 1e-4) return;
            Point mousePoint = e.getPoint(); double logicalX = (mousePoint.x - offsetX) / oldScale; double logicalY = (mousePoint.y - offsetY) / oldScale; offsetX = mousePoint.x - logicalX * scale; offsetY = mousePoint.y - logicalY * scale;
            logger.trace("Zoom: scale={}, offsetX={}, offsetY={}", String.format("%.2f", scale), String.format("%.1f", offsetX), String.format("%.1f", offsetY));
            revalidate(); repaint(); // Revalidate needed for scrollbars
        }
    }
}