package de.anton.pv.analyser.pv_analyzer.view;

// Imports bleiben unverändert...
import de.anton.pv.analyser.pv_analyzer.model.AnalysisModel;
import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import de.anton.pv.analyser.pv_analyzer.model.ModuleInfo;
import de.anton.pv.analyser.pv_analyzer.algorithms.MyOPTICS;

import org.jfree.chart.ChartFactory;
import org.jfree.chart.ChartPanel;
import org.jfree.chart.JFreeChart;
import org.jfree.chart.axis.NumberAxis;
import org.jfree.chart.labels.ItemLabelAnchor;
import org.jfree.chart.labels.ItemLabelPosition;
import org.jfree.chart.labels.XYItemLabelGenerator;
import org.jfree.chart.labels.XYToolTipGenerator;
import org.jfree.chart.plot.PlotOrientation;
import org.jfree.chart.plot.PlotRenderingInfo;
import org.jfree.chart.plot.XYPlot;
import org.jfree.chart.renderer.xy.XYItemRenderer;
import org.jfree.chart.renderer.xy.XYLineAndShapeRenderer;
import org.jfree.chart.ui.RectangleInsets;
import org.jfree.chart.ui.TextAnchor;
import org.jfree.chart.ChartRenderingInfo;
import org.jfree.chart.util.ShapeUtils;
import org.jfree.data.xy.XYDataset;
import org.jfree.data.xy.XYSeries;
import org.jfree.data.xy.XYSeriesCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * Scatter plot dialog showing selected variables, cluster results, and MPP.
 * Includes tooltips, panning (left-click drag), and point labels (X.Y format).
 * ADDED: Disabled Double Buffering, ensured Plot Notify is true.
 */
public class ClusterPlotDialog extends JDialog {

    private static final Logger logger = LoggerFactory.getLogger(ClusterPlotDialog.class);

    private final ChartPanel chartPanel;
    private final JFreeChart scatterPlot;
    private final XYSeriesCollection dataset;
    private String currentXVarName = "?";
    private String currentYVarName = "?";
    private List<List<CalculatedDataPoint>> seriesDataMap = new ArrayList<>();

    // --- Farben und Formen (unverändert) ---
    private static final Color[] CLUSTER_COLORS = { new Color(31, 119, 180), new Color(255, 127, 14), new Color(44, 160, 44), new Color(214, 39, 40), new Color(148, 103, 189), new Color(140, 86, 75), new Color(227, 119, 194), new Color(127, 127, 127), new Color(188, 189, 34), new Color(23, 190, 207), Color.BLUE.darker(), Color.RED.darker(), Color.GREEN.darker(), Color.ORANGE.darker(), Color.MAGENTA.darker(), Color.CYAN.darker() };
    private static final Color NOISE_COLOR = Color.BLACK;
    private static final Shape NOISE_SHAPE = ShapeUtils.createDiamond(3.0f);
    private static final Shape CLUSTER_SHAPE = ShapeUtils.createRegularCross(3.0f, 1.0f);
    private static final Shape MPP_SHAPE = ShapeUtils.createDiamond(6.0f);
    private static final Color MPP_COLOR = Color.RED;
    private static final Font POINT_LABEL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 8);
    // --- Ende Farben und Formen ---

    private Point panLastPoint = null;

    public ClusterPlotDialog(Frame owner) {
        super(owner, "Cluster Analyse - Scatter Plot", false);
        dataset = new XYSeriesCollection();
        scatterPlot = createChart(dataset);
        chartPanel = new ChartPanel(scatterPlot);
        chartPanel.setPreferredSize(new Dimension(800, 600));

        // --- NEU: Double Buffering deaktivieren ---
        //System.out.println(">>> DEBUG: Disabling Double Buffering on ChartPanel");
        chartPanel.setDoubleBuffered(false);
        // ----------------------------------------

        chartPanel.setMouseWheelEnabled(true);
        chartPanel.setDomainZoomable(true);
        chartPanel.setRangeZoomable(true);
        chartPanel.setMouseZoomable(false);
        chartPanel.setPopupMenu(null);
        chartPanel.setInitialDelay(0);

//        // *** DEBUG Listeners (unverändert) ***
//        chartPanel.addMouseListener(new MouseAdapter() {
//             @Override
//             public void mousePressed(MouseEvent e) {
//                 System.out.println(">>> ChartPanel Raw MousePressed: " + e.getPoint());
//             }
//             @Override
//             public void mouseReleased(MouseEvent e) {
//                 System.out.println(">>> ChartPanel Raw MouseReleased: " + e.getPoint());
//             }
//        });
//        chartPanel.addMouseMotionListener(new MouseMotionAdapter() {
//            @Override
//            public void mouseDragged(MouseEvent e) {
//                System.out.println(">>> ChartPanel Raw MouseDragged: " + e.getPoint());
//            }
//        });
//        chartPanel.addMouseWheelListener(new MouseWheelListener() {
//            @Override
//            public void mouseWheelMoved(MouseWheelEvent e) {
//                System.out.println(">>> ChartPanel Raw MouseWheelMoved: Rot=" + e.getWheelRotation() + " at " + e.getPoint());
//                System.out.println(">>> ChartPanel Raw MouseWheelMoved: Forcing repaint...");
//                chartPanel.repaint();
//            }
//        });
//        // *** ENDE DEBUG Listeners ***


        // ----- Panning Handler hinzufügen -----
        PanningHandler panningHandler = new PanningHandler();
        chartPanel.addMouseListener(panningHandler);
        chartPanel.addMouseMotionListener(panningHandler);


        setContentPane(chartPanel);
        pack();
        setLocationRelativeTo(owner);
    }

    private JFreeChart createChart(XYSeriesCollection dataset) {
        JFreeChart chart = ChartFactory.createScatterPlot(
                "Cluster Analyse", "X-Variable", "Y-Variable", dataset,
                PlotOrientation.VERTICAL, true, true, false);

        XYPlot plot = (XYPlot) chart.getPlot();

        // --- NEU: Plot Notification sicherstellen ---
//        System.out.println(">>> DEBUG: Ensuring plot notification is enabled");
        plot.setNotify(true);
        // ----------------------------------------

        plot.setBackgroundPaint(Color.WHITE);
        plot.setDomainGridlinePaint(Color.LIGHT_GRAY);
        plot.setRangeGridlinePaint(Color.LIGHT_GRAY);
        plot.setAxisOffset(new RectangleInsets(5.0, 5.0, 5.0, 5.0));

        XYLineAndShapeRenderer renderer = new XYLineAndShapeRenderer(false, true);
        renderer.setDefaultToolTipGenerator(new VariableToolTipGenerator());
        renderer.setDefaultItemLabelGenerator(new PointLabelGenerator());
        renderer.setDefaultItemLabelsVisible(true);
        renderer.setDefaultItemLabelFont(POINT_LABEL_FONT);

        ItemLabelPosition position = new ItemLabelPosition(ItemLabelAnchor.OUTSIDE6, TextAnchor.TOP_CENTER);
        renderer.setDefaultPositiveItemLabelPosition(position);
        renderer.setDefaultNegativeItemLabelPosition(position);

        plot.setRenderer(renderer);

        NumberAxis domainAxis = (NumberAxis) plot.getDomainAxis();
        domainAxis.setStandardTickUnits(NumberAxis.createIntegerTickUnits());
        NumberAxis rangeAxis = (NumberAxis) plot.getRangeAxis();

        // Auto-Range wird jetzt in updatePlot() verwaltet
        // System.out.println(">>> DEBUG: Disabling auto range for Domain Axis (in createChart)");
        // domainAxis.setAutoRange(false);
        // System.out.println(">>> DEBUG: Disabling auto range for Range Axis (in createChart)");
        // rangeAxis.setAutoRange(false);

        return chart;
    }

    // updatePlot Methode (unverändert mit Auto-Range Management)
    public void updatePlot(List<CalculatedDataPoint> data, int numberOfClusters, ModuleInfo moduleInfo,
                           String xVarName, String yVarName) {
        if (!SwingUtilities.isEventDispatchThread()) {
            SwingUtilities.invokeLater(() -> updatePlot(data, numberOfClusters, moduleInfo, xVarName, yVarName));
            return;
        }
        logger.debug("Updating cluster plot for X='{}', Y='{}'", xVarName, yVarName);
        this.currentXVarName = xVarName != null ? xVarName : "?";
        this.currentYVarName = yVarName != null ? yVarName : "?";
        this.seriesDataMap.clear();
        dataset.removeAllSeries();

        XYPlot plot = (XYPlot) scatterPlot.getPlot(); // Plot holen

        if (data == null || data.isEmpty()) {
            scatterPlot.setTitle("Keine Daten zum Anzeigen");
            plot.getDomainAxis().setLabel("?");
            plot.getRangeAxis().setLabel("?");
             // Wenn keine Daten da sind, ist Auto-Range ok oder sogar gewünscht
            logger.debug(">>> DEBUG: Enabling auto range (no data)");
             ((NumberAxis) plot.getDomainAxis()).setAutoRange(true);
             ((NumberAxis) plot.getRangeAxis()).setAutoRange(true);
            chartPanel.repaint();
            return;
        }

        scatterPlot.setTitle("MyOPTICS Clustering (" + this.currentYVarName + " vs " + this.currentXVarName + ")");
        plot.getDomainAxis().setLabel(this.currentXVarName);
        plot.getRangeAxis().setLabel(this.currentYVarName);

        // Auto-Range aktivieren, damit die Achsen sich an die neuen Daten anpassen
        logger.debug(">>> DEBUG: Enabling auto range before adding data...");
        ((NumberAxis) plot.getDomainAxis()).setAutoRange(true);
        ((NumberAxis) plot.getRangeAxis()).setAutoRange(true);

        // Daten hinzufügen (Code unverändert)...
        Map<Integer, List<CalculatedDataPoint>> pointsByCluster = data.stream()
                .filter(p -> p != null && !Double.isNaN(getVariableValue(p, this.currentXVarName)) && !Double.isNaN(getVariableValue(p, this.currentYVarName)))
                .collect(Collectors.groupingBy(CalculatedDataPoint::getClusterGroup));
        XYItemRenderer renderer = plot.getRenderer();
        int seriesIndex = 0;
        seriesDataMap = new ArrayList<>();
        List<CalculatedDataPoint> noisePoints = pointsByCluster.getOrDefault(MyOPTICS.NOISE, Collections.emptyList());
        List<CalculatedDataPoint> currentSeriesPointsForLabel = new ArrayList<>();
        if (!noisePoints.isEmpty()) {
            XYSeries noiseSeries = new XYSeries("Noise (" + noisePoints.size() + ")", false, true);
            for (CalculatedDataPoint point : noisePoints) {
                double xValue = getVariableValue(point, this.currentXVarName);
                double yValue = getVariableValue(point, this.currentYVarName);
                if (!Double.isNaN(xValue) && !Double.isNaN(yValue)) {
                    noiseSeries.add(xValue, yValue);
                    currentSeriesPointsForLabel.add(point);
                }
            }
            if (noiseSeries.getItemCount() > 0) {
                dataset.addSeries(noiseSeries);
                renderer.setSeriesPaint(seriesIndex, NOISE_COLOR);
                renderer.setSeriesShape(seriesIndex, NOISE_SHAPE);
                renderer.setSeriesItemLabelsVisible(seriesIndex, true);
                seriesDataMap.add(new ArrayList<>(currentSeriesPointsForLabel));
                seriesIndex++;
            } else { seriesDataMap.add(Collections.emptyList()); }
        } else { seriesDataMap.add(Collections.emptyList()); }
        List<Integer> clusterKeys = pointsByCluster.keySet().stream()
                .filter(key -> key != MyOPTICS.NOISE).sorted().collect(Collectors.toList());
        for (int clusterId : clusterKeys) {
            List<CalculatedDataPoint> clusterPoints = pointsByCluster.getOrDefault(clusterId, Collections.emptyList());
            XYSeries clusterSeries = new XYSeries("Cluster " + clusterId + " (" + clusterPoints.size() + ")", false, true);
            currentSeriesPointsForLabel.clear();
            for (CalculatedDataPoint point : clusterPoints) {
                double xValue = getVariableValue(point, this.currentXVarName);
                double yValue = getVariableValue(point, this.currentYVarName);
                if (!Double.isNaN(xValue) && !Double.isNaN(yValue)) {
                    clusterSeries.add(xValue, yValue);
                    currentSeriesPointsForLabel.add(point);
                }
            }
            if (clusterSeries.getItemCount() > 0) {
                dataset.addSeries(clusterSeries);
                Color clusterColor = CLUSTER_COLORS[clusterId % CLUSTER_COLORS.length];
                renderer.setSeriesPaint(seriesIndex, clusterColor);
                renderer.setSeriesShape(seriesIndex, CLUSTER_SHAPE);
                renderer.setSeriesItemLabelsVisible(seriesIndex, true);
                seriesDataMap.add(new ArrayList<>(currentSeriesPointsForLabel));
                seriesIndex++;
            } else { seriesDataMap.add(Collections.emptyList()); }
        }
        boolean plotMPP = moduleInfo != null && AnalysisModel.VAR_DC_SPANNUNG.equals(this.currentXVarName) && AnalysisModel.VAR_DC_LEISTUNG.equals(this.currentYVarName) && !Double.isNaN(moduleInfo.getVmppV()) && !Double.isNaN(moduleInfo.getPmppKW());
        if (plotMPP) {
             double mppX = moduleInfo.getVmppV(); double mppY = moduleInfo.getPmppKW(); XYSeries mppSeries = new XYSeries(String.format("MPP (%.1f V, %.3f kW)", mppX, mppY), false, false); mppSeries.add(mppX, mppY); dataset.addSeries(mppSeries); renderer.setSeriesPaint(seriesIndex, MPP_COLOR); renderer.setSeriesShape(seriesIndex, MPP_SHAPE); renderer.setSeriesItemLabelsVisible(seriesIndex, false); seriesDataMap.add(Collections.emptyList()); seriesIndex++; logger.debug("MPP point added to plot.");
        } else { logger.trace("MPP point not plotted."); }
        // Daten hinzugefügt...

        // Auto-Range wieder deaktivieren, damit Pan/Zoom funktioniert
        logger.debug(">>> DEBUG: Disabling auto range AFTER adding data...");
        ((NumberAxis) plot.getDomainAxis()).setAutoRange(false);
        ((NumberAxis) plot.getRangeAxis()).setAutoRange(false);

        logger.debug("Cluster plot updated with {} series for axes '{}' vs '{}'. seriesDataMap size: {}", dataset.getSeriesCount(), this.currentXVarName, this.currentYVarName, seriesDataMap.size());
        // Das Hinzufügen der Daten sollte ein Repaint triggern, aber zur Sicherheit:
        chartPanel.repaint();
    }

    // getVariableValue (unverändert)
    public double getVariableValue(CalculatedDataPoint point, String varName) {
        if (point == null || varName == null) return Double.NaN;
        switch (varName) {
            case AnalysisModel.VAR_DC_LEISTUNG: return point.getDcLeistungKW();
            case AnalysisModel.VAR_SPEZ_LEISTUNG: return point.getSpezifischeLeistung();
            case AnalysisModel.VAR_DC_SPANNUNG: return point.getDcSpannungV();
            case AnalysisModel.VAR_STROM_STRING: return point.getStromJeStringA();
            case AnalysisModel.VAR_OHM: return point.getOhm();
            default:
                logger.warn("Unknown variable name requested for plotting: {}", varName);
                return Double.NaN;
        }
    }

    // VariableToolTipGenerator (unverändert)
    private class VariableToolTipGenerator implements XYToolTipGenerator {
        @Override
        public String generateToolTip(XYDataset xyDataset, int series, int item) {
            Comparable seriesKey = xyDataset.getSeriesKey(series);
            double xValue = xyDataset.getXValue(series, item);
            double yValue = xyDataset.getYValue(series, item);
            String xStr = String.format("%.3f", xValue);
            String yStr = String.format("%.3f", yValue);
            return String.format("%s: %s = %s, %s = %s",
                    seriesKey, ClusterPlotDialog.this.currentXVarName, xStr,
                    ClusterPlotDialog.this.currentYVarName, yStr);
        }
    }

    // PointLabelGenerator (unverändert)
    private class PointLabelGenerator implements XYItemLabelGenerator {
        private Pattern namePattern = Pattern.compile("^TR(?:#| )?(\\d+)\\.(\\d+)$", Pattern.CASE_INSENSITIVE);
        @Override
        public String generateLabel(XYDataset dataset, int series, int item) {
           if (series >= 0 && series < seriesDataMap.size()) {
               List<CalculatedDataPoint> pointsInSeries = seriesDataMap.get(series);
               if (pointsInSeries != null && item >= 0 && item < pointsInSeries.size()) {
                   CalculatedDataPoint cdp = pointsInSeries.get(item);
                   if (cdp != null && cdp.getName() != null) {
                       return parseTrackerIdShort(cdp.getName());
                   }
               }
           }
            return null;
        }
        private String parseTrackerIdShort(String fullName) {
            if (fullName == null) return "?";
            Matcher matcher = namePattern.matcher(fullName.trim());
            if (matcher.matches()) { return matcher.group(1) + "." + matcher.group(2); }
            logger.trace("Could not parse short tracker ID from '{}'", fullName);
            return "?";
        }
    }

    // PanningHandler (unverändert mit explizitem Repaint)
    private class PanningHandler extends MouseAdapter {
        @Override
        public void mousePressed(MouseEvent e) {
//            System.out.println("PanningHandler mousePressed");
            if (SwingUtilities.isLeftMouseButton(e)) {
                ClusterPlotDialog.this.panLastPoint = e.getPoint();
                chartPanel.setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
//                System.out.println("PanningHandler requesting focus...");
                boolean requested = chartPanel.requestFocusInWindow();
//                System.out.println("PanningHandler focus requested: " + requested);
            }
        }
        @Override
        public void mouseReleased(MouseEvent e) {
//            System.out.println("PanningHandler mouseReleased");
            if (SwingUtilities.isLeftMouseButton(e)) {
                ClusterPlotDialog.this.panLastPoint = null;
                chartPanel.setCursor(Cursor.getDefaultCursor());
            }
        }
        @Override
        public void mouseDragged(MouseEvent e) {
//            System.out.println("PanningHandler mouseDragged: last=" + ClusterPlotDialog.this.panLastPoint + ", current=" + e.getPoint());
            if (ClusterPlotDialog.this.panLastPoint != null && SwingUtilities.isLeftMouseButton(e)) {
                int dx = e.getX() - panLastPoint.x; int dy = e.getY() - panLastPoint.y;
//                System.out.println("PanningHandler Delta: dx=" + dx + ", dy=" + dy);
                XYPlot plot = (XYPlot) scatterPlot.getPlot();
                ChartRenderingInfo chartInfo = chartPanel.getChartRenderingInfo();
                if (chartInfo == null) {
//                    System.err.println("PANNING ERROR: ChartRenderingInfo is NULL! Cannot pan.");
                    ClusterPlotDialog.this.panLastPoint = e.getPoint(); return;
                }
                PlotRenderingInfo info = chartInfo.getPlotInfo();
                if (info == null) {
//                    System.err.println("PANNING ERROR: PlotRenderingInfo is NULL! Cannot pan.");
                    ClusterPlotDialog.this.panLastPoint = e.getPoint(); return;
                }
                Rectangle2D dataArea = info.getDataArea();
//                System.out.println("PanningHandler DataArea: " + dataArea);
                if (dataArea != null && dataArea.contains(ClusterPlotDialog.this.panLastPoint)) {
//                    System.out.println("PanningHandler: Panning inside data area...");
                    Point2D panStartPoint2D = chartPanel.translateScreenToJava2D(ClusterPlotDialog.this.panLastPoint);
                    Point2D panEndPoint2D = chartPanel.translateScreenToJava2D(e.getPoint());
//                    System.out.println("PanningHandler Java2D Coords: Start=" + panStartPoint2D + ", End=" + panEndPoint2D);
                    try {
//                        System.out.println("PanningHandler: Calling panDomainAxes...");
                        plot.panDomainAxes(panStartPoint2D.getX() - panEndPoint2D.getX(), info, panStartPoint2D);
//                        System.out.println("PanningHandler: Calling panRangeAxes...");
                        plot.panRangeAxes(panStartPoint2D.getY() - panEndPoint2D.getY(), info, panStartPoint2D);
//                        System.out.println("PanningHandler: Pan methods called successfully.");
//                        System.out.println("PanningHandler: Forcing repaint...");
                        chartPanel.repaint(); // EXPLIZIT REPAINT
                    } catch (Exception panEx) {
//                        System.err.println("PANNING ERROR during pan call: " + panEx.getMessage());
                        panEx.printStackTrace();
                    }
                    ClusterPlotDialog.this.panLastPoint = e.getPoint();
                } else {
//                    System.out.println("PanningHandler: Drag detected OUTSIDE data area or dataArea is null.");
                    ClusterPlotDialog.this.panLastPoint = e.getPoint();
                }
            } else {
//                System.out.println("PanningHandler: Dragged without left button or panLastPoint is null.");
            }
        }
    }
}