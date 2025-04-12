package de.anton.pv.analyser.pv_analyzer.algorithms;

import de.anton.pv.analyser.pv_analyzer.model.CalculatedDataPoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.BiFunction;

/**
 * Basic OPTICS implementation with interruption support.
 */
public class MyOPTICS {

    private static final Logger logger = LoggerFactory.getLogger(MyOPTICS.class);
    public static final double UNDEFINED = Double.POSITIVE_INFINITY;
    public static final int NOISE = -1;

    private final List<CalculatedDataPoint> points;
    private final double epsilon;
    private final int minPts;
    private final BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunction;

    private final Map<CalculatedDataPoint, Double> coreDistance;
    private final Map<CalculatedDataPoint, Double> reachabilityDistance;
    private final Set<CalculatedDataPoint> processed;
    private final List<CalculatedDataPoint> orderedList;
    private int currentClusterId;

    public MyOPTICS(List<CalculatedDataPoint> points, double epsilon, int minPts,
                    BiFunction<CalculatedDataPoint, CalculatedDataPoint, Double> distanceFunction) {
        this.points = Objects.requireNonNull(points);
        if (epsilon <= 0) throw new IllegalArgumentException("Epsilon must be positive.");
        if (minPts <= 0) throw new IllegalArgumentException("MinPts must be positive.");
        this.epsilon = epsilon;
        this.minPts = minPts;
        this.distanceFunction = Objects.requireNonNull(distanceFunction);
        int initialCapacity = points.size();
        this.coreDistance = new HashMap<>(initialCapacity);
        this.reachabilityDistance = new HashMap<>(initialCapacity);
        this.processed = new HashSet<>(initialCapacity);
        this.orderedList = new ArrayList<>(initialCapacity);
    }

    /** Executes OPTICS, checking for thread interruption. */
    public void run() throws InterruptedException {
        if (points.isEmpty()) { logger.info("MyOPTICS skipped: No points."); return; }
        logger.debug("Starting MyOPTICS: eps={}, minPts={}, points={}", epsilon, minPts, points.size());

        for (CalculatedDataPoint p : points) {
             if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS cancelled during init.");
            if (p != null) {
                reachabilityDistance.put(p, UNDEFINED);
                try { p.setClusterGroup(NOISE); } catch (Exception e) { logger.error("Error setting cluster group for {}", p.getName(), e); }
            }
        }
        currentClusterId = -1;

        for (CalculatedDataPoint point : points) {
             if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS cancelled during main loop.");
            if (point != null && !processed.contains(point)) {
                 if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS cancelled before order expansion.");
                expandClusterOrder(point); // throws InterruptedException
            }
        }

        int noiseCount = 0; int assignedCount = 0; int numClusters = currentClusterId + 1;
        for(CalculatedDataPoint p : orderedList) { if (p != null) { if(p.getClusterGroup() == NOISE) noiseCount++; else assignedCount++; } }
        logger.debug("MyOPTICS finished. Order size {}. Assigned {} to {} clusters (IDs 0-{}). {} noise points.",
                     orderedList.size(), assignedCount, numClusters, currentClusterId, noiseCount);
    }

    /** Expands cluster order, checking for interruption. */
    private void expandClusterOrder(CalculatedDataPoint startPoint) throws InterruptedException {
        logger.trace("Expanding order from: {}", startPoint.getName());
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS expansion cancelled at start.");

        List<CalculatedDataPoint> neighbors = regionQuery(startPoint);
        processed.add(startPoint); orderedList.add(startPoint);
        double startCoreDist = calculateCoreDistance(startPoint, neighbors);
        coreDistance.put(startPoint, startCoreDist);
        assignSimpleClusterId(startPoint, UNDEFINED);

        if (startCoreDist != UNDEFINED) {
            PriorityQueue<CalculatedDataPoint> seeds = new PriorityQueue<>(Comparator.comparingDouble(p -> reachabilityDistance.getOrDefault(p, UNDEFINED)));
            updateSeeds(seeds, startPoint, neighbors); // Fast

            while (!seeds.isEmpty()) {
                 if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS expansion cancelled in loop.");
                CalculatedDataPoint currentPoint = seeds.poll();
                if (processed.contains(currentPoint)) continue;

                if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS expansion cancelled before query.");
                List<CalculatedDataPoint> currentNeighbors = regionQuery(currentPoint);
                processed.add(currentPoint); orderedList.add(currentPoint);
                assignSimpleClusterId(currentPoint, reachabilityDistance.getOrDefault(currentPoint, UNDEFINED));
                double currentCoreDist = calculateCoreDistance(currentPoint, currentNeighbors);
                coreDistance.put(currentPoint, currentCoreDist);

                if (currentCoreDist != UNDEFINED) {
                     if (Thread.currentThread().isInterrupted()) throw new InterruptedException("OPTICS expansion cancelled before update.");
                    updateSeeds(seeds, currentPoint, currentNeighbors); // Fast
                }
            }
        } else {
            logger.trace("Start point {} is NOT core.", startPoint.getName());
        }
    }

    /** Updates seeds. Fast per call. */
    private void updateSeeds(PriorityQueue<CalculatedDataPoint> seeds, CalculatedDataPoint corePoint, List<CalculatedDataPoint> neighbors) {
        double coreDist = coreDistance.get(corePoint);
        for (CalculatedDataPoint neighbor : neighbors) {
            if (neighbor == null || processed.contains(neighbor)) continue;
            double dist = distanceFunction.apply(corePoint, neighbor);
            if (Double.isNaN(dist) || Double.isInfinite(dist)) { logger.warn("Invalid distance between {} and {}. Skipping update.", corePoint.getName(), neighbor.getName()); continue; }
            double newReachability = Math.max(coreDist, dist);
            double currentReachability = reachabilityDistance.getOrDefault(neighbor, UNDEFINED);
            if (newReachability < currentReachability) {
                reachabilityDistance.put(neighbor, newReachability);
                seeds.remove(neighbor); // O(n)
                seeds.offer(neighbor);  // O(log n)
            }
        }
    }

    /** Assigns simple cluster ID. Fast. */
     private void assignSimpleClusterId(CalculatedDataPoint point, double reachability) {
         if (point == null) return;
         boolean previousWasNoiseOrUndefined = true;
         int lastIndex = orderedList.size() - 2;
         if (lastIndex >= 0) {
              CalculatedDataPoint prevPoint = orderedList.get(lastIndex);
              if (prevPoint != null) {
                  double prevReach = reachabilityDistance.getOrDefault(prevPoint, UNDEFINED);
                  previousWasNoiseOrUndefined = (prevReach >= epsilon || prevReach == UNDEFINED);
              }
         }
         if (reachability < epsilon && reachability != UNDEFINED) {
             if (previousWasNoiseOrUndefined) {
                 currentClusterId++;
                 logger.trace("Starting new cluster {} with {} (reach={:.3f})", currentClusterId, point.getName(), reachability);
             } else {
                 logger.trace("Continuing cluster {} with {} (reach={:.3f})", currentClusterId, point.getName(), reachability);
             }
             point.setClusterGroup(currentClusterId);
         } else {
             point.setClusterGroup(NOISE);
             logger.trace("Point {} marked as NOISE (reach={})", point.getName(), reachability == UNDEFINED ? "UNDEF" : String.format("%.3f", reachability));
         }
     }

    /** Calculates core distance. Fast per call. */
    private double calculateCoreDistance(CalculatedDataPoint point, List<CalculatedDataPoint> neighbors) {
        if (neighbors == null || neighbors.size() < minPts) return UNDEFINED;
        List<Double> distances = new ArrayList<>(neighbors.size());
        for (CalculatedDataPoint neighbor : neighbors) {
            if (neighbor != null && !point.equals(neighbor)) {
                 try {
                    double dist = distanceFunction.apply(point, neighbor);
                    if (!Double.isNaN(dist) && !Double.isInfinite(dist)) distances.add(dist);
                 } catch (Exception e) { logger.warn("Error calc distance for core dist {}<->{}: {}", point.getName(), neighbor.getName(), e.getMessage()); }
            }
        }
        if (distances.size() < minPts - 1) return UNDEFINED;
        Collections.sort(distances);
        // The (minPts-1)-th neighbor's distance is at index (minPts-1)-1 = minPts-2
        double coreDist = distances.get(minPts - 2);
        return (coreDist <= epsilon) ? coreDist : UNDEFINED; // Core distance only defined if <= epsilon
    }

    /** Finds neighbors (O(n)). No internal interruption check. */
    private List<CalculatedDataPoint> regionQuery(CalculatedDataPoint centerPoint) {
        List<CalculatedDataPoint> neighbors = new ArrayList<>();
        if (centerPoint == null) { logger.warn("regionQuery called with null!"); return neighbors; }
        for (CalculatedDataPoint potentialNeighbor : points) {
            if (potentialNeighbor != null) {
                try {
                    double dist = distanceFunction.apply(centerPoint, potentialNeighbor);
                    if (!Double.isNaN(dist) && !Double.isInfinite(dist) && dist <= epsilon) {
                        neighbors.add(potentialNeighbor);
                    }
                } catch (Exception e) { logger.error("Error calc distance in regionQuery {}<->{}: {}", centerPoint.getName(), potentialNeighbor.getName(), e.getMessage());}
            }
        }
        return neighbors;
    }

    // --- Getters ---
    public List<CalculatedDataPoint> getOrderedList() { return Collections.unmodifiableList(orderedList); }
    public Map<CalculatedDataPoint, Double> getReachabilityDistances() { return Collections.unmodifiableMap(reachabilityDistance); }
    public Map<CalculatedDataPoint, Double> getCoreDistances() { return Collections.unmodifiableMap(coreDistance); }
}