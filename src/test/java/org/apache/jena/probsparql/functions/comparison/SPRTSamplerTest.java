package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SPRTSamplerTest {

    @Test
    void testEarlyAcceptForClearlySimilarPair() {
        SPRTSampler sampler = new SPRTSampler(42L, 0.05, 0.05, 0.3);

        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = create1DGaussian(0.2, 1.0);

        double[] result = sampler.computeJSDWithStats(p, q, 5_000);

        assertTrue(result[0] < 0.3, "Similar pair should stay below the similarity threshold");
        assertTrue(result[1] < 5_000, "Clearly similar pair should stop before using the full budget");
        assertEquals(1, sampler.getEarlyAccepted(), "Sampler should record an early similar decision");
        assertEquals(0, sampler.getEarlyRejected(), "Sampler should not record a dissimilar decision here");
    }

    @Test
    void testEarlyRejectForClearlyDissimilarPair() {
        SPRTSampler sampler = new SPRTSampler(42L, 0.05, 0.05, 0.3);

        GMMValue p = create1DGaussian(0.0, 0.2);
        GMMValue q = create1DGaussian(5.0, 0.2);

        double[] result = sampler.computeJSDWithStats(p, q, 5_000);

        assertTrue(result[0] > 0.3, "Clearly separated pair should exceed the similarity threshold");
        assertTrue(result[1] < 5_000, "Clearly dissimilar pair should stop before using the full budget");
        assertEquals(0, sampler.getEarlyAccepted(), "Sampler should not record a similar decision here");
        assertEquals(1, sampler.getEarlyRejected(), "Sampler should record an early dissimilar decision");
    }

    @Test
    void testFallsBackToFullBudgetWhenNoDecisionCheckRuns() {
        SPRTSampler sampler = new SPRTSampler(42L, 0.05, 0.05, 0.3);

        GMMValue p = create1DGaussian(0.0, 1.0);
        GMMValue q = create1DGaussian(0.6, 1.0);

        double[] result = sampler.computeJSDWithStats(p, q, 40);

        assertEquals(40.0, result[1], "Budget smaller than one decision interval should use all samples");
        assertEquals(1, sampler.getFullSamples(), "Sampler should record one full-budget run");
        assertEquals(0, sampler.getEarlyAccepted(), "No early accept should be recorded");
        assertEquals(0, sampler.getEarlyRejected(), "No early reject should be recorded");
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
