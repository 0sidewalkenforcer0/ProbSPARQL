package org.apache.jena.probsparql.exp2;

import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.BoundsFilterSampler;

/**
 * Cascading pruning evaluator for the Exp2 pruned similarity join.
 *
 * <p>Levels actually executed:</p>
 * <pre>
 * Level 1 — Dimensionality check                O(1)
 * Level 2 — Discretized (DPI) JSD lower bound    O(d·B·K)
 * Level 5 — Full JSD estimation                  O(N·K)
 * </pre>
 *
 * <p>Levels 3 and 4 are intentionally absent. They were to be moment-based bounds
 * (variance ratio, mean distance), but those expressions are not valid lower bounds on
 * JSD, so pruning with them would discard matching pairs. Their counters remain in
 * {@link PruningStats} at zero so the results schema is stable; see
 * {@link BoundsFilterSampler} for why they cannot be used.</p>
 *
 * <p>Each level records its contribution in the supplied {@link PruningStats}, which
 * maintains the invariant that every candidate pair is counted exactly once.</p>
 */
public class PrunedSimJoinEvaluator {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(PrunedSimJoinEvaluator.class);

    private final double tolerance;
    private final double tailProbability;
    private final BoundsFilterSampler boundsChecker;
    private final PruningStats stats;

    public PrunedSimJoinEvaluator(double tolerance, double tailProbability, PruningStats stats) {
        this.tolerance    = tolerance;
        this.tailProbability = tailProbability;
        this.boundsChecker = new BoundsFilterSampler(tolerance);
        this.stats        = stats;
    }

    /**
     * Evaluate whether the pair (g1, g2) satisfies JSD(g1, g2) &lt;= tolerance.
     *
     * <p>GMM pairs use the pruning cascade. Other supported distribution
     * datatypes fall back to full polymorphic similarity evaluation.</p>
     *
     * @param leftNode  RDF Node carrying the left supported distribution literal
     * @param rightNode RDF Node carrying the right supported distribution literal
     * @return true iff the pair should be kept (JSD &lt;= tolerance)
     */
    public boolean evaluate(Node leftNode, Node rightNode) {
        stats.totalPairs++;

        Object leftValue = leftNode.getLiteralValue();
        Object rightValue = rightNode.getLiteralValue();
        if (leftValue instanceof GMMValue g1 && rightValue instanceof GMMValue g2) {
            return evaluateGMMs(g1, g2, leftNode, rightNode);
        }
        return evaluateWithoutPruning(leftNode, rightNode);
    }

    private boolean evaluateGMMs(GMMValue g1, GMMValue g2,
                                  Node leftNode, Node rightNode) {
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
            stats.prunedByDiscretizedJSD++;
            return false;
        }

        // ── Levels 3 and 4: intentionally absent (see class javadoc) ────────────

        // ── Level 5: full JSD estimation ────────────────────────────────────────
        return evaluateFull(leftNode, rightNode);
    }

    private boolean evaluateWithoutPruning(Node leftNode, Node rightNode) {
        return evaluateFull(leftNode, rightNode);
    }

    /**
     * Full JSD estimation, counted as the terminal cascade level.
     *
     * <p>A pair whose evaluation throws has no defined verdict. It is excluded from the
     * result, but recorded in {@link PruningStats#evaluationFailures} rather than
     * silently treated as a non-match: otherwise a systematic fault would be reported
     * as a legitimate zero-result join.</p>
     */
    private boolean evaluateFull(Node leftNode, Node rightNode) {
        stats.computedFullJSD++;
        try {
            double jsd = ProbSPARQL.evaluateSimilarity(leftNode, rightNode, tolerance, tailProbability);
            boolean passes = jsd <= tolerance;
            if (passes) {
                stats.resultCount++;
            }
            return passes;
        } catch (RuntimeException e) {
            stats.evaluationFailures++;
            LOG.warn("Similarity evaluation failed for a candidate pair; excluding it. {}",
                e.toString());
            return false;
        }
    }
}
