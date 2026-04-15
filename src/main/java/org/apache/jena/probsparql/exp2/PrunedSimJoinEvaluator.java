package org.apache.jena.probsparql.exp2;

import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.BoundsFilterSampler;

/**
 * Five-level cascading pruning evaluator for the Exp2 pruned similarity join.
 *
 * <pre>
 * Level 1 — Dimensionality check         O(1)
 * Level 2 — Mean-distance lower bound    O(K)
 * Level 3 — Variance-ratio lower bound   O(K)
 * Level 4 — Combined analytic bounds     O(K)  (calls BoundsFilterSampler.checkBounds)
 * Level 5 — Full JSD (MC sampling)       O(N·K)
 * </pre>
 *
 * Each level records its contribution in the supplied {@link PruningStats}.
 */
public class PrunedSimJoinEvaluator {

    private final double tolerance;
    private final BoundsFilterSampler boundsChecker;
    private final PruningStats stats;

    public PrunedSimJoinEvaluator(double tolerance, PruningStats stats) {
        this.tolerance    = tolerance;
        this.boundsChecker = new BoundsFilterSampler(tolerance);
        this.stats        = stats;
    }

    /**
     * Evaluate whether the pair (g1, g2) satisfies JSD(g1, g2) &lt;= tolerance.
     *
     * @param leftNode  RDF Node carrying the left GMMValue literal
     * @param rightNode RDF Node carrying the right GMMValue literal
     * @return true iff the pair should be kept (JSD &lt;= tolerance)
     */
    public boolean evaluate(Node leftNode, Node rightNode) {
        GMMValue g1 = (GMMValue) leftNode.getLiteralValue();
        GMMValue g2 = (GMMValue) rightNode.getLiteralValue();
        return evaluateGMMs(g1, g2, leftNode, rightNode);
    }

    private boolean evaluateGMMs(GMMValue g1, GMMValue g2,
                                  Node leftNode, Node rightNode) {
        stats.totalPairs++;

        // ── Level 1: dimensionality check ──────────────────────────────────
        if (g1.getDimensions() != g2.getDimensions()) {
            stats.prunedByDim++;
            return false;
        }

        // ── Level 2: discretized JSD lower bound (DPI-based, valid for GMMs) ─────
        // computeDiscretizedJSD is a guaranteed lower bound by the Data Processing
        // Inequality: JSD(binned_P||binned_Q) <= JSD(P||Q).  Pairs where even the
        // coarsened histogram JSD exceeds the tolerance can be safely pruned.
        double discJSD = boundsChecker.computeDiscretizedJSD(g1, g2, 30);
        if (discJSD > tolerance) {
            stats.prunedByMean++;
            return false;
        }

        // ── Level 3: (disabled — variance bound not valid for GMMs) ─────────────
        // stats.prunedByVariance remains 0 for this run.

        // ── Level 4: (disabled — checkBounds uses invalid bounds for GMMs) ───────
        // stats.prunedByBounds remains 0 for this run.

        // ── Level 5: full JSD computation (MC sampling) ────────────────────
        stats.computedFullJSD++;
        try {
            double jsd = ProbSPARQL.JSDivergence(leftNode, rightNode);
            boolean passes = jsd <= tolerance;
            if (passes) {
                stats.resultCount++;
            }
            return passes;
        } catch (Exception e) {
            // If computation fails, conservatively exclude the pair
            return false;
        }
    }
}
