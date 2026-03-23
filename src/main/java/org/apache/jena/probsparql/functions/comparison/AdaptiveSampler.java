package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;

/**
 * V5: Adaptive Sampler (Complete Solution)
 * 
 * Combines all optimization techniques:
 * 1. Bounds Filter: O(1) analytical bounds to reject clearly different pairs
 * 2. SPRT: Sequential Probability Ratio Test for early termination
 * 3. Stratified Sampling: Improved accuracy with proportional component sampling
 * 
 * Decision Flow:
 * ┌─────────────────────────────────────────┐
 * │  Start                                 │
 * └────────┬────────────────────────────────┘
 *          ▼
 * ┌─────────────────────────────────────────┐
 * │  Bounds Check (O(1) analytical)          │
 * │  JSD >= boundsThreshold?                │
 * └────────┬──────────────┬────────────────┘
 *          │Yes            │No
 *          ▼               ▼
 * ┌─────────────┐  ┌────────────────────────┐
 * │  REJECT     │  │  SPRT Test             │
 * │  (filtered) │  │  (early termination)   │
 * └─────────────┘  └───────────┬────────────┘
 *                              │
 *                  ┌────────────┼────────────┐
 *                  ▼            ▼            ▼
 *           ┌──────────┐  ┌─────────┐  ┌──────────┐
 *           │ Accept   │  │ Continue│  │  Full    │
 *           │ (close)  │  │ SPRT    │  │ Sampling │
 *           └──────────┘  └────┬────┘  └──────────┘
 *                               │
 *                               ▼
 *                    ┌───────────────────────┐
 *                    │ Stratified Sampling   │
 *                    │ (final computation)   │
 *                    └───────────────────────┘
 * 
 * @author ProbSPARQL Team
 */
public class AdaptiveSampler {
    
    private final BoundsFilterSampler boundsFilter;
    private final SPRTSampler sprtSampler;
    private final StratifiedSampler stratifiedSampler;
    
    // Thresholds
    private final double boundsThreshold;
    private final double sprtEpsilon;
    
    // Statistics
    private int totalPairs = 0;
    private int filteredByBounds = 0;    // Rejected by bounds
    private int earlyBySPRT = 0;        // Early terminated by SPRT
    private int fullStratified = 0;      // Required full stratified sampling
    
    private long boundsTimeNanos = 0;
    private long sprtTimeNanos = 0;
    private long stratifiedTimeNanos = 0;
    
    public AdaptiveSampler(double boundsThreshold, double alpha, double beta, double epsilon) {
        this.boundsThreshold = boundsThreshold;
        this.sprtEpsilon = epsilon;
        
        this.boundsFilter = new BoundsFilterSampler(boundsThreshold);
        this.sprtSampler = new SPRTSampler(42, alpha, beta, epsilon);
        this.stratifiedSampler = new StratifiedSampler(42);
    }
    
    /**
     * Compute JSD adaptively using the full V5 pipeline.
     * Returns: {jsdValue, actualSamplesUsed, method (0=bounds, 1=sprt, 2=stratified)}
     */
    public double[] computeJSDAdaptive(GMMValue p, GMMValue q, int maxSamples) {
        totalPairs++;
        
        long startTime = System.nanoTime();
        
        // Step 1: Bounds Filter (O(1))
        double[] boundsResult = boundsFilter.checkBounds(p, q);
        boundsTimeNanos += System.nanoTime() - startTime;
        
        if (boundsResult[0] > 0.5) {
            // Filtered by bounds
            filteredByBounds++;
            return new double[] {boundsResult[1], 0, 0};  // method 0 = bounds
        }
        
        // Step 2: SPRT (if not filtered)
        startTime = System.nanoTime();
        double[] sprtResult = sprtSampler.computeJSDWithStats(p, q, maxSamples / 2);
        sprtTimeNanos += System.nanoTime() - startTime;
        
        if (sprtResult[1] < maxSamples / 2) {
            // Early termination by SPRT
            earlyBySPRT++;
            return new double[] {sprtResult[0], sprtResult[1], 1};  // method 1 = SPRT
        }
        
        // Step 3: Full stratified sampling
        startTime = System.nanoTime();
        double jsd = stratifiedSampler.computeJSD(p, q, maxSamples);
        stratifiedTimeNanos += System.nanoTime() - startTime;
        
        fullStratified++;
        return new double[] {jsd, maxSamples, 2};  // method 2 = stratified
    }
    
    /**
     * Simple compute JSD (for compatibility).
     */
    public double computeJSD(GMMValue p, GMMValue q, int samples) {
        return computeJSDAdaptive(p, q, samples)[0];
    }
    
    // Statistics getters
    public int getTotalPairs() { return totalPairs; }
    public int getFilteredByBounds() { return filteredByBounds; }
    public int getEarlyBySPRT() { return earlyBySPRT; }
    public int getFullStratified() { return fullStratified; }
    
    public double getBoundsFilterRate() {
        return totalPairs > 0 ? (double) filteredByBounds / totalPairs : 0.0;
    }
    
    public double getSPRTEarlyRate() {
        return totalPairs > 0 ? (double) earlyBySPRT / totalPairs : 0.0;
    }
    
    public double getFullSamplingRate() {
        return totalPairs > 0 ? (double) fullStratified / totalPairs : 0.0;
    }
    
    public long getTotalTimeNanos() {
        return boundsTimeNanos + sprtTimeNanos + stratifiedTimeNanos;
    }
    
    public void resetStats() {
        totalPairs = 0;
        filteredByBounds = 0;
        earlyBySPRT = 0;
        fullStratified = 0;
        boundsTimeNanos = 0;
        sprtTimeNanos = 0;
        stratifiedTimeNanos = 0;
    }
    
    /**
     * Print detailed statistics.
     */
    public void printStats() {
        System.out.println("=== Adaptive Sampler Statistics ===");
        System.out.println("Total Pairs: " + totalPairs);
        System.out.printf("Bounds Filtered: %d (%.1f%%)%n", 
            filteredByBounds, getBoundsFilterRate() * 100);
        System.out.printf("SPRT Early Term: %d (%.1f%%)%n", 
            earlyBySPRT, getSPRTEarlyRate() * 100);
        System.out.printf("Full Stratified: %d (%.1f%%)%n", 
            fullStratified, getFullSamplingRate() * 100);
        System.out.printf("Total Time: %.3f ms%n", getTotalTimeNanos() / 1e6);
        System.out.printf("  - Bounds: %.3f ms%n", boundsTimeNanos / 1e6);
        System.out.printf("  - SPRT: %.3f ms%n", sprtTimeNanos / 1e6);
        System.out.printf("  - Stratified: %.3f ms%n", stratifiedTimeNanos / 1e6);
        System.out.println("=================================");
    }
}
