package org.apache.jena.probsparql.exp2;

/**
 * Collects per-level pruning statistics for the Exp2 pruned similarity join.
 *
 * Invariant: prunedByDim + prunedByMean + prunedByVariance + prunedByBounds
 *            + computedFullJSD == totalPairs
 */
public class PruningStats {

    public long totalPairs       = 0;
    public long prunedByDim      = 0;   // L1: incompatible dimensionality
    public long prunedByMean     = 0;   // L2: mean-distance bound conclusive
    public long prunedByVariance = 0;   // L3: variance-ratio bound conclusive
    public long prunedByBounds   = 0;   // L4: full analytic JSD bounds conclusive
    public long computedFullJSD  = 0;   // L5: fell through to MC sampling
    public long resultCount      = 0;   // pairs that passed (JSD <= theta)

    public void reset() {
        totalPairs       = 0;
        prunedByDim      = 0;
        prunedByMean     = 0;
        prunedByVariance = 0;
        prunedByBounds   = 0;
        computedFullJSD  = 0;
        resultCount      = 0;
    }

    /** Add counts from another stats object into this one. */
    public void aggregate(PruningStats other) {
        totalPairs       += other.totalPairs;
        prunedByDim      += other.prunedByDim;
        prunedByMean     += other.prunedByMean;
        prunedByVariance += other.prunedByVariance;
        prunedByBounds   += other.prunedByBounds;
        computedFullJSD  += other.computedFullJSD;
        resultCount      += other.resultCount;
    }

    /** Fraction of pairs that did not require full JSD computation. */
    public double pruningRate() {
        if (totalPairs == 0) return 0.0;
        return (double)(totalPairs - computedFullJSD) / totalPairs;
    }

    @Override
    public String toString() {
        return String.format(
            "PruningStats{total=%d, dim=%d, mean=%d, var=%d, bounds=%d, full=%d, results=%d, rate=%.1f%%}",
            totalPairs, prunedByDim, prunedByMean, prunedByVariance,
            prunedByBounds, computedFullJSD, resultCount, pruningRate() * 100.0);
    }
}
