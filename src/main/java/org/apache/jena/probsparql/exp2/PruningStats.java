package org.apache.jena.probsparql.exp2;

/**
 * Collects per-level pruning statistics for the Exp2 pruned similarity join.
 *
 * <p>Each candidate pair is accounted for at exactly one level, so:</p>
 * <pre>
 * prunedByDim + prunedByDiscretizedJSD + prunedByVariance + prunedByBounds
 *     + computedFullJSD == totalPairs
 * </pre>
 * <p>{@link #invariantHolds()} checks this. The reported {@link #pruningRate()} is only
 * meaningful while it holds, so callers should verify rather than assume it.</p>
 *
 * @see org.apache.jena.probsparql.exp2.PrunedSimJoinEvaluator for which levels are active
 */
public class PruningStats {

    public long totalPairs       = 0;

    /** L1: rejected because the operands have different dimensionality. */
    public long prunedByDim      = 0;

    /**
     * L2: rejected because the discretized (DPI) JSD lower bound already exceeded the
     * threshold.
     *
     * <p>Named for the bound actually computed. This counter was previously called
     * {@code prunedByMean} and described as a mean-distance bound, which did not match
     * the implementation and would have been misreported in results tables.</p>
     */
    public long prunedByDiscretizedJSD = 0;

    /** L3: reserved; the variance-ratio bound is not valid for GMMs and is disabled. */
    public long prunedByVariance = 0;

    /** L4: reserved; superseded by the L2 bound and currently disabled. */
    public long prunedByBounds   = 0;

    /** L5: fell through to full JSD estimation. */
    public long computedFullJSD  = 0;

    /** Pairs that passed the predicate (JSD <= theta). */
    public long resultCount      = 0;

    /**
     * Pairs whose evaluation threw and were excluded. Counted separately because such a
     * pair has no defined verdict; it is a subset of {@link #computedFullJSD}.
     */
    public long evaluationFailures = 0;

    public PruningStats() {}

    public PruningStats(PruningStats other) {
        this.totalPairs             = other.totalPairs;
        this.prunedByDim            = other.prunedByDim;
        this.prunedByDiscretizedJSD = other.prunedByDiscretizedJSD;
        this.prunedByVariance       = other.prunedByVariance;
        this.prunedByBounds         = other.prunedByBounds;
        this.computedFullJSD        = other.computedFullJSD;
        this.resultCount            = other.resultCount;
        this.evaluationFailures     = other.evaluationFailures;
    }

    public void reset() {
        totalPairs             = 0;
        prunedByDim            = 0;
        prunedByDiscretizedJSD = 0;
        prunedByVariance       = 0;
        prunedByBounds         = 0;
        computedFullJSD        = 0;
        resultCount            = 0;
        evaluationFailures     = 0;
    }

    /** Add counts from another stats object into this one. */
    public void aggregate(PruningStats other) {
        totalPairs             += other.totalPairs;
        prunedByDim            += other.prunedByDim;
        prunedByDiscretizedJSD += other.prunedByDiscretizedJSD;
        prunedByVariance       += other.prunedByVariance;
        prunedByBounds         += other.prunedByBounds;
        computedFullJSD        += other.computedFullJSD;
        resultCount            += other.resultCount;
        evaluationFailures     += other.evaluationFailures;
    }

    /** Sum of the per-level counters, which must equal {@link #totalPairs}. */
    public long accountedPairs() {
        return prunedByDim + prunedByDiscretizedJSD + prunedByVariance
            + prunedByBounds + computedFullJSD;
    }

    /** True when every candidate pair has been accounted for at exactly one level. */
    public boolean invariantHolds() {
        return accountedPairs() == totalPairs;
    }

    /** Fraction of pairs that did not require full JSD computation. */
    public double pruningRate() {
        if (totalPairs == 0) return 0.0;
        return (double)(totalPairs - computedFullJSD) / totalPairs;
    }

    @Override
    public String toString() {
        return String.format(
            "PruningStats{total=%d, dim=%d, discJSD=%d, var=%d, bounds=%d, full=%d, "
                + "results=%d, failures=%d, rate=%.1f%%%s}",
            totalPairs, prunedByDim, prunedByDiscretizedJSD, prunedByVariance,
            prunedByBounds, computedFullJSD, resultCount, evaluationFailures,
            pruningRate() * 100.0,
            invariantHolds() ? "" : ", INVARIANT VIOLATED accounted=" + accountedPairs());
    }
}
