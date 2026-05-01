package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimilarityEvaluatorTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("probsparql.mode");
    }

    @Test
    void testV3UsesPerCallThresholdForSequentialStopping() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V3_SPRT);

        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = create1DGaussian(1.0, 1.0);

        SimilarityEvaluator.EvaluationResult loose =
            new SimilarityEvaluator(0.3).evaluateWithDetails(p, q);
        SimilarityEvaluator.EvaluationResult strict =
            new SimilarityEvaluator(0.05).evaluateWithDetails(p, q);

        assertTrue(loose.samplesUsed() < strict.samplesUsed(),
            "A looser query threshold should allow earlier SPRT termination");
        assertEquals(SimilarityEvaluator.Pathway.SPRT, loose.pathway());
        assertEquals(SimilarityEvaluator.Pathway.SPRT, strict.pathway());
    }

    @Test
    void testV3UsesPerCallTailProbabilityForSequentialStopping() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V3_SPRT);

        GMMValue p = create1DGaussian(0.0, 1.0);
        SimilarityEvaluator.EvaluationResult relaxed = null;
        SimilarityEvaluator.EvaluationResult conservative = null;

        for (double meanShift = 0.1; meanShift <= 3.0; meanShift += 0.1) {
            GMMValue q = create1DGaussian(meanShift, 1.0);
            SimilarityEvaluator.EvaluationResult candidateRelaxed =
                new SimilarityEvaluator(0.3, 0.20, 0.20).evaluateWithDetails(p, q);
            SimilarityEvaluator.EvaluationResult candidateConservative =
                new SimilarityEvaluator(0.3, 0.01, 0.01).evaluateWithDetails(p, q);

            if (candidateRelaxed.samplesUsed() < candidateConservative.samplesUsed()) {
                relaxed = candidateRelaxed;
                conservative = candidateConservative;
                break;
            }
        }

        assertTrue(relaxed != null && conservative != null,
            "Test setup should find a pair where a larger tail probability causes earlier SPRT stopping");
        assertEquals(SimilarityEvaluator.Pathway.SPRT, relaxed.pathway());
        assertEquals(SimilarityEvaluator.Pathway.SPRT, conservative.pathway());
    }

    @Test
    void testV5UsesPerCallThresholdForAdaptiveBoundsDecision() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V5_ADAPTIVE);

        BoundsFilterSampler probe = new BoundsFilterSampler(0.0);
        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = null;
        double lowerBound = -1.0;

        for (double meanShift = 0.5; meanShift <= 3.0; meanShift += 0.1) {
            GMMValue candidate = create1DGaussian(meanShift, 1.0);
            double candidateBound = probe.computeDiscretizedJSD(p, candidate, 30);
            if (candidateBound > 0.05 && candidateBound < 0.3) {
                q = candidate;
                lowerBound = candidateBound;
                break;
            }
        }

        assertTrue(q != null,
            "Test setup should find a pair whose discretized lower bound falls between the two thresholds");

        SimilarityEvaluator.EvaluationResult strict =
            new SimilarityEvaluator(0.05).evaluateWithDetails(p, q);
        SimilarityEvaluator.EvaluationResult loose =
            new SimilarityEvaluator(0.3).evaluateWithDetails(p, q);

        assertEquals(SimilarityEvaluator.Pathway.ADAPTIVE_BOUNDS, strict.pathway(),
            "Strict threshold should trigger the adaptive bounds short-circuit");
        assertNotEquals(SimilarityEvaluator.Pathway.ADAPTIVE_BOUNDS, loose.pathway(),
            "Looser threshold should allow the pair to continue beyond the bounds stage");
        assertEquals(lowerBound, strict.score(), 1e-4,
            "Bounds short-circuit should return the discretized lower bound");
    }

    @Test
    void testThresholdAwareHelperUsesEvaluateSimilarityPath() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V5_ADAPTIVE);

        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = create1DGaussian(1.0, 1.0);
        Node leftNode = NodeFactory.createLiteralDT(p.toJSON(), GMMDatatype.INSTANCE);
        Node rightNode = NodeFactory.createLiteralDT(q.toJSON(), GMMDatatype.INSTANCE);

        double helperScore = ProbSPARQL.evaluateSimilarity(leftNode, rightNode, 0.3);
        double evaluatorScore = new SimilarityEvaluator(0.3).evaluate(p, q);

        assertEquals(evaluatorScore, helperScore, 1e-12,
            "Threshold-aware helper should delegate to the new SimilarityEvaluator");
    }

    @Test
    void testTailAwareHelperUsesEvaluateSimilarityPath() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V3_SPRT);

        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = create1DGaussian(1.0, 1.0);
        Node leftNode = NodeFactory.createLiteralDT(p.toJSON(), GMMDatatype.INSTANCE);
        Node rightNode = NodeFactory.createLiteralDT(q.toJSON(), GMMDatatype.INSTANCE);

        double helperScore = ProbSPARQL.evaluateSimilarity(leftNode, rightNode, 0.3, 0.05);
        double evaluatorScore = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(p, q);

        assertEquals(evaluatorScore, helperScore, 1e-12,
            "Tail-aware helper should delegate to the per-query SimilarityEvaluator");
    }

    private GMMValue create1DGaussian(double mean, double std) {
        return new GMMValue(
            1,
            1,
            "full",
            new double[]{1.0},
            new double[][]{{mean}},
            new double[][][]{{{std * std}}}
        );
    }
}
