package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Soundness properties of the V4 analytic filter.
 *
 * <p>The filter is only usable in one direction: {@code bound > threshold} may reject,
 * {@code bound <= threshold} must not decide. These tests pin both the numerical
 * guarantee (the bound never exceeds the true JSD) and the API contract that reports
 * when the bound is inconclusive.</p>
 */
class BoundsSoundnessTest {

    /** Numerical reference JSD by Simpson quadrature; accurate to well under 1e-6. */
    private static double exactJSD1D(GMMValue p, GMMValue q) {
        double lo = -50.0;
        double hi = 50.0;
        int n = 200_000;                 // even
        double h = (hi - lo) / n;
        double acc = 0.0;
        for (int i = 0; i <= n; i++) {
            double weight = (i == 0 || i == n) ? 1.0 : (i % 2 == 1 ? 4.0 : 2.0);
            acc += weight * jsdIntegrand(p, q, new double[]{lo + i * h});
        }
        return acc * h / 3.0;
    }

    private static double jsdIntegrand(GMMValue p, GMMValue q, double[] x) {
        double pp = Math.exp(p.logPdf(x));
        double qq = Math.exp(q.logPdf(x));
        double m = 0.5 * (pp + qq);
        if (m <= 0.0) return 0.0;
        double s = 0.0;
        if (pp > 0.0) s += 0.5 * pp * Math.log(pp / m);
        if (qq > 0.0) s += 0.5 * qq * Math.log(qq / m);
        return s;
    }

    @Test
    void discretizedBoundNeverExceedsTrueJSD() {
        BoundsFilterSampler bounds = new BoundsFilterSampler(0.0);
        Random rng = new Random(20260806L);

        for (int trial = 0; trial < 25; trial++) {
            GMMValue p = randomGMM1D(rng);
            GMMValue q = randomGMM1D(rng);

            double bound = bounds.computeDiscretizedJSD(p, q, 32);
            double exact = exactJSD1D(p, q);

            assertTrue(bound <= exact + 1e-6,
                "DPI bound must not exceed the true JSD: bound=" + bound + " exact=" + exact);
        }
    }

    @Test
    void boundIsAvailableForMultidimensionalGMMs() {
        // Regression: the bound used to be defined only for d=1 and returned 0.0
        // otherwise, so V4 scored every multidimensional pair as identical and a
        // DIVJOIN degenerated into the full cross product.
        GMMValue near = gaussian2D(0.0, 0.0, 1.0);
        GMMValue far = gaussian2D(100.0, 100.0, 1.0);

        double bound = new BoundsFilterSampler(0.0).computeDiscretizedJSD(near, far, 32);
        assertTrue(bound > 0.6,
            "Well-separated 2-D GMMs must produce a large lower bound, got: " + bound);

        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V4_BOUNDS);
        double score = new SimilarityEvaluator(0.1, 0.05, 0.05).evaluate(near, far);
        assertTrue(score > 0.1,
            "V4 must not report separated multidimensional GMMs as within tolerance, got: " + score);
    }

    @Test
    void inconclusiveBoundIsRefinedOnTheDecisionPath() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V4_BOUNDS);

        // Identical distributions: the bound is 0, which cannot settle a decision.
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(0.0, 1.0);

        SimilarityEvaluator.EvaluationResult result =
            new SimilarityEvaluator(0.3, 0.05, 0.05).evaluateWithDetails(p, q);

        assertEquals(SimilarityEvaluator.Pathway.BOUNDS_REFINED, result.pathway(),
            "An inconclusive bound must be refined by sampling before it can be thresholded");
        assertTrue(result.samplesUsed() > 0, "Refinement must report the samples it consumed");
    }

    @Test
    void scoringUsageReportsTheBoundWithoutRefining() {
        // fn:jsdMode measures each estimator as-is, so V4 must report its bound and
        // consume no samples even when the bound cannot settle a decision. This is what
        // keeps the published Exp3 V4 accuracy and latency figures reproducible.
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(0.0, 1.0);

        SimilarityEvaluator.EvaluationResult scored = SimilarityEvaluator
            .forScoring(JSDivergenceConfig.MODE_V4_BOUNDS, 0.3, 0.05, 0.05)
            .evaluateWithDetails(p, q);

        assertEquals(SimilarityEvaluator.Pathway.BOUNDS, scored.pathway());
        assertEquals(0, scored.samplesUsed(), "Scoring V4 must not sample");
    }

    @Test
    void scoringAndDecisionAgreeWhenTheBoundIsConclusive() {
        // The two intents may only diverge where the bound is inconclusive.
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(50.0, 1.0);

        SimilarityEvaluator.EvaluationResult scored = SimilarityEvaluator
            .forScoring(JSDivergenceConfig.MODE_V4_BOUNDS, 0.05, 0.05, 0.05)
            .evaluateWithDetails(p, q);
        SimilarityEvaluator.EvaluationResult decided =
            new SimilarityEvaluator(JSDivergenceConfig.MODE_V4_BOUNDS, 0.05, 0.05, 0.05)
                .evaluateWithDetails(p, q);

        assertEquals(SimilarityEvaluator.Pathway.BOUNDS, scored.pathway());
        assertEquals(SimilarityEvaluator.Pathway.BOUNDS, decided.pathway());
        assertEquals(scored.score(), decided.score(), 0.0);
    }

    @Test
    void otherModesAreUnaffectedByUsage() {
        // Only V4 distinguishes the two intents; every other mode returns a genuine
        // estimate, so scoring and decision must coincide exactly.
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(1.0, 2.0);

        for (String mode : new String[]{JSDivergenceConfig.MODE_V1_MC,
                                        JSDivergenceConfig.MODE_V2_STRATIFIED,
                                        JSDivergenceConfig.MODE_V3_SPRT,
                                        JSDivergenceConfig.MODE_V5_ADAPTIVE}) {
            double scored = SimilarityEvaluator.forScoring(mode, 0.3, 0.05, 0.05).evaluate(p, q);
            double decided = new SimilarityEvaluator(mode, 0.3, 0.05, 0.05).evaluate(p, q);
            assertEquals(scored, decided, 0.0, "Usage must not change mode " + mode);
        }
    }

    @Test
    void conclusiveBoundShortCircuitsWithoutSampling() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_V4_BOUNDS);

        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(50.0, 1.0);

        SimilarityEvaluator.EvaluationResult result =
            new SimilarityEvaluator(0.05, 0.05, 0.05).evaluateWithDetails(p, q);

        assertEquals(SimilarityEvaluator.Pathway.BOUNDS, result.pathway());
        assertEquals(0, result.samplesUsed(), "A conclusive bound must not sample");
    }

    @Test
    void deprecatedMomentHeuristicsAreNotValidBounds() {
        // Documents why checkBounds must not use them: the mean-distance expression
        // overshoots the true JSD, so pruning on it would discard matching pairs.
        BoundsFilterSampler bounds = new BoundsFilterSampler(0.0);
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(1.0, 1.0);

        double heuristic = bounds.computeMeanDistanceBound(p, q);
        double exact = exactJSD1D(p, q);

        assertFalse(heuristic <= exact,
            "If this ever becomes a valid bound the deprecation note should be revisited");
    }

    // ------------------------------------------------------------------ helpers

    private static GMMValue gaussian1D(double mean, double variance) {
        return new GMMValue(1, 1, "full", new double[]{1.0},
            new double[][]{{mean}}, new double[][][]{{{variance}}});
    }

    private static GMMValue gaussian2D(double m0, double m1, double variance) {
        return new GMMValue(1, 2, "full", new double[]{1.0},
            new double[][]{{m0, m1}},
            new double[][][]{{{variance, 0.0}, {0.0, variance}}});
    }

    private static GMMValue randomGMM1D(Random rng) {
        int k = 1 + rng.nextInt(3);
        double[] weights = new double[k];
        double sum = 0.0;
        for (int i = 0; i < k; i++) {
            weights[i] = 0.1 + rng.nextDouble();
            sum += weights[i];
        }
        for (int i = 0; i < k; i++) {
            weights[i] /= sum;
        }
        double[][] means = new double[k][1];
        double[][][] covs = new double[k][][];
        for (int i = 0; i < k; i++) {
            means[i][0] = -3.0 + 6.0 * rng.nextDouble();
            covs[i] = new double[][]{{0.2 + 2.5 * rng.nextDouble()}};
        }
        return new GMMValue(k, 1, "full", weights, means, covs);
    }
}
