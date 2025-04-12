package de.anton.pv.analyser.pv_analyzer.algorithms;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Basic DBSCAN implementation with interruption support.
 */
public class MyDBSCAN {

    private static final Logger logger = LoggerFactory.getLogger(MyDBSCAN.class);
    public static final int NOISE = -1;
    public static final int UNCLASSIFIED = -2;

    private final List<CalculatedDataPoint> points;
    private final double epsilon;
    private final int minPts;
    private final BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunction;
    private final Map<CalculatedDataPoint, Integer> pointStatus;

    public MyDBSCAN(List<CalculatedDataPoint> points, double epsilon, int minPts,
                    BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunction) {
        this.points = Objects.requireNonNull(points, "Input point list cannot be null.");
        if (epsilon <= 0) throw new IllegalArgumentException("Epsilon must be positive.");
        if (minPts <= 0) throw new IllegalArgumentException("MinPts must be positive.");
        this.epsilon = epsilon;
        this.minPts = minPts;
        this.distanceFunction = Objects.requireNonNull(distanceFunction, "Distance function cannot be null.");
        this.pointStatus = new HashMap<>(points.size());
    }

    /** Executes DBSCAN, checking for thread interruption. */
    public void run() throws InterruptedException {
        if (points.isEmpty()) { logger.info("MyDBSCAN skipped: No points."); return; }
        logger.debug("Starting MyDBSCAN: eps={}, minPts={}, points={}", epsilon, minPts, points.size());

        for (CalculatedDataPoint p : points) {
             if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled during init.");
            if (p != null) {
                pointStatus.put(p, UNCLASSIFIED);
                try { p.setOutlier(false); } catch (Exception e) { logger.error("Error setting outlier status for {}", p.getName(), e); }
            }
        }

        int currentClusterId = 0;
        for (CalculatedDataPoint p : points) {
             if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled during main loop.");
            if (p == null || pointStatus.get(p) != UNCLASSIFIED) continue;

            List<CalculatedDataPoint> neighbors = regionQuery(p);
            if (neighbors.size() < minPts) {
                pointStatus.put(p, NOISE);
            } else {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled before cluster expansion.");
                expandCluster(p, neighbors, currentClusterId); // throws InterruptedException
                currentClusterId++;
            }
        }

        int noiseCount = 0, assignedCount = 0;
        for (CalculatedDataPoint p : points) {
            if (p != null) {
                Integer status = pointStatus.get(p);
                boolean isOutlier = (status == null || status == NOISE || status == UNCLASSIFIED);
                try { p.setOutlier(isOutlier); } catch (Exception e) { logger.error("Error setting final outlier status for {}", p.getName(), e); }
                if (isOutlier) noiseCount++; else assignedCount++;
            }
        }
        logger.debug("MyDBSCAN finished. Assigned {} points to {} clusters. {} noise points.",
                     assignedCount, currentClusterId, noiseCount);
    }

    /** Expands a cluster, checking for interruption. */
    private void expandCluster(CalculatedDataPoint corePoint, List<CalculatedDataPoint> initialNeighbors, int clusterId)
            throws InterruptedException {
        pointStatus.put(corePoint, clusterId);
        Queue<CalculatedDataPoint> seedQueue = new LinkedList<>(initialNeighbors);
        Set<CalculatedDataPoint> queuedNeighbors = new HashSet<>(initialNeighbors);

        while (!seedQueue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled during cluster expansion.");
            CalculatedDataPoint currentNeighbor = seedQueue.poll();
            Integer neighborStatus = pointStatus.get(currentNeighbor);

            if (neighborStatus == null || neighborStatus == UNCLASSIFIED || neighborStatus == NOISE) {
                pointStatus.put(currentNeighbor, clusterId);
                if (neighborStatus == null || neighborStatus == UNCLASSIFIED) {
                     if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled before neighbor query.");
                    List<CalculatedDataPoint> currentNeighborNeighbors = regionQuery(currentNeighbor);
                    if (currentNeighborNeighbors.size() >= minPts) {
                        for (CalculatedDataPoint nn : currentNeighborNeighbors) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("DBSCAN cancelled during neighbor processing.");
                            if (nn == null) continue;
                            Integer nnStatus = pointStatus.get(nn);
                            if ((nnStatus == null || nnStatus == UNCLASSIFIED || nnStatus == NOISE) && queuedNeighbors.add(nn)) {
                                seedQueue.offer(nn);
                            }
                        }
                    }
                }
            }
        }
    }

    /** Finds neighbors (O(n)). No internal interruption check for performance. */
    private List<CalculatedDataPoint> regionQuery(CalculatedDataPoint centerPoint) {
        List<CalculatedDataPoint> neighbors = new ArrayList<>();
        if (centerPoint == null) { logger.warn("regionQuery called with null centerPoint!"); return neighbors; }
        for (CalculatedDataPoint potentialNeighbor : points) {
            if (potentialNeighbor == null) continue;
            try {
                double dist = distanceFunction.apply(centerPoint, potentialNeighbor);
                if (!Double.isNaN(dist) && !Double.isInfinite(dist) && dist <= epsilon) {
                    neighbors.add(potentialNeighbor);
                }
            } catch (Exception e) {
                logger.error("Error calculating distance between '{}' and '{}'. Skipping neighbor.",
                             centerPoint.getName() != null ? centerPoint.getName() : "<?>",
                             potentialNeighbor.getName() != null ? potentialNeighbor.getName() : "<?>", e);
            }
        }
        return neighbors;
    }
}