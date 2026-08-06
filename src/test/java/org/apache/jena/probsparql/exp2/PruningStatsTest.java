package org.apache.jena.probsparql.exp2;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Accounting properties of the Exp2 pruning cascade.
 *
 * <p>The pruning rate published for the experiment is only interpretable if every
 * candidate pair is counted at exactly one cascade level, and if pairs that could not be
 * evaluated at all are distinguishable from genuine non-matches.</p>
 */
class PruningStatsTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_GT_1K);
    }

    @Test
    void everyPairIsAccountedForAtExactlyOneLevel() {
        PruningStats stats = new PruningStats();
        PrunedSimJoinEvaluator evaluator = new PrunedSimJoinEvaluator(0.1, 0.05, stats);

        Node[] operands = {
            gmmNode(gaussian1D(0.0, 1.0)),
            gmmNode(gaussian1D(0.05, 1.0)),     // nearly identical: falls through to full JSD
            gmmNode(gaussian1D(40.0, 1.0)),     // far: pruned by the DPI bound
            gmmNode(gaussian2D(0.0, 0.0)),      // different dimensionality: pruned at L1
        };

        for (Node left : operands) {
            for (Node right : operands) {
                evaluator.evaluate(left, right);
            }
        }

        assertEquals(operands.length * operands.length, stats.totalPairs);
        assertTrue(stats.invariantHolds(),
            "Cascade counters must sum to totalPairs, got: " + stats);
        assertTrue(stats.prunedByDim > 0, "Mixed dimensionality should be pruned at L1");
        assertTrue(stats.prunedByDiscretizedJSD > 0, "Separated pairs should be pruned by the bound");
        assertTrue(stats.computedFullJSD > 0, "Near-identical pairs should reach full estimation");
        assertEquals(0, stats.evaluationFailures, "No pair in this fixture should fail to evaluate");
    }

    @Test
    void disabledLevelsStayAtZero() {
        // Levels 3 and 4 are deliberately absent because the moment-based bounds they
        // would have used are not valid lower bounds on JSD. Their counters exist only
        // to keep the results CSV schema stable.
        PruningStats stats = new PruningStats();
        PrunedSimJoinEvaluator evaluator = new PrunedSimJoinEvaluator(0.2, 0.05, stats);

        evaluator.evaluate(gmmNode(gaussian1D(0.0, 1.0)), gmmNode(gaussian1D(1.0, 1.0)));

        assertEquals(0, stats.prunedByVariance);
        assertEquals(0, stats.prunedByBounds);
    }

    @Test
    void invariantViolationIsDetectable() {
        PruningStats stats = new PruningStats();
        stats.totalPairs = 10;
        stats.computedFullJSD = 4;
        assertFalse(stats.invariantHolds());
        assertTrue(stats.toString().contains("INVARIANT VIOLATED"),
            "A broken invariant must be visible in the reported string, got: " + stats);
    }

    @Test
    void pruningRateReflectsPairsThatSkippedFullEstimation() {
        PruningStats stats = new PruningStats();
        stats.totalPairs = 100;
        stats.prunedByDiscretizedJSD = 75;
        stats.computedFullJSD = 25;

        assertTrue(stats.invariantHolds());
        assertEquals(0.75, stats.pruningRate(), 1e-12);
    }

    // ------------------------------------------------------------------ helpers

    private static GMMValue gaussian1D(double mean, double variance) {
        return new GMMValue(1, 1, "full", new double[]{1.0},
            new double[][]{{mean}}, new double[][][]{{{variance}}});
    }

    private static GMMValue gaussian2D(double m0, double m1) {
        return new GMMValue(1, 2, "full", new double[]{1.0},
            new double[][]{{m0, m1}}, new double[][][]{{{1.0, 0.0}, {0.0, 1.0}}});
    }

    private static Node gmmNode(GMMValue gmm) {
        return NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
    }
}
