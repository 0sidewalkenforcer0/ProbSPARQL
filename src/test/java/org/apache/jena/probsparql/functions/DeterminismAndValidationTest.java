package org.apache.jena.probsparql.functions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.comparison.PolyJSD;
import org.apache.jena.probsparql.functions.comparison.SimilarityEvaluator;
import org.apache.jena.sparql.expr.NodeValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two properties every sampling-based function here must have.
 *
 * <p><b>Determinism.</b> A SPARQL expression is expected to be referentially
 * transparent: {@code prob:jsd(?a, ?b)} must return the same value each time it is
 * evaluated on the same operands, whatever else the engine evaluated first and on
 * whichever thread. Estimators that draw from an ambient or shared RNG break this
 * and make benchmark runs unreproducible.</p>
 *
 * <p><b>Input validation.</b> Non-finite parameters must be rejected at construction.
 * Range checks written as plain comparisons silently admit NaN, after which every
 * density evaluation returns NaN and the query yields nonsense instead of an error.</p>
 */
class DeterminismAndValidationTest {

    // ---------------------------------------------------------------- determinism

    @Test
    void klDivergenceIsStableAcrossRepeatedEvaluations() {
        Node p = gmmNode(gaussian1D(0.0, 1.0));
        Node q = gmmNode(gaussian1D(1.2, 1.0));
        KLDivergence kl = new KLDivergence();

        double first = kl.exec(NodeValue.makeNode(p), NodeValue.makeNode(q)).getDouble();
        for (int i = 0; i < 5; i++) {
            double repeat = kl.exec(NodeValue.makeNode(p), NodeValue.makeNode(q)).getDouble();
            assertEquals(first, repeat, 0.0,
                "prob:kldivergence must be a pure function of its operands");
        }
    }

    @Test
    void klDivergenceStaysAsymmetric() {
        // Determinism must not be achieved by collapsing D(P||Q) and D(Q||P).
        Node p = gmmNode(gaussian1D(0.0, 1.0));
        Node q = gmmNode(gaussian1D(0.0, 4.0));
        KLDivergence kl = new KLDivergence();

        double pq = kl.exec(NodeValue.makeNode(p), NodeValue.makeNode(q)).getDouble();
        double qp = kl.exec(NodeValue.makeNode(q), NodeValue.makeNode(p)).getDouble();

        assertTrue(Math.abs(pq - qp) > 1e-6,
            "KL divergence is asymmetric; got " + pq + " both ways");
    }

    @Test
    void polyJsdIsStableAndSymmetric() {
        Node a = gmmNode(gaussian1D(0.0, 1.0));
        Node b = gmmNode(gaussian1D(0.8, 2.0));
        PolyJSD jsd = new PolyJSD();

        double forward = jsd.exec(NodeValue.makeNode(a), NodeValue.makeNode(b)).getDouble();
        double repeat = jsd.exec(NodeValue.makeNode(a), NodeValue.makeNode(b)).getDouble();
        double reverse = jsd.exec(NodeValue.makeNode(b), NodeValue.makeNode(a)).getDouble();

        assertEquals(forward, repeat, 0.0, "prob:jsd must be a pure function of its operands");
        assertEquals(forward, reverse, 0.0, "JSD is symmetric, so its estimate must be too");
    }

    @Test
    void crossTypeJsdIsStableAcrossRepeatedEvaluations() {
        Node hist = histogramNode(new double[]{0.0, 1.0, 2.0, 3.0},
            new double[]{0.2, 0.5, 0.3});
        Node dir = dirichletNode(new double[]{2.0, 3.0});
        PolyJSD jsd = new PolyJSD();

        double first = jsd.exec(NodeValue.makeNode(hist), NodeValue.makeNode(dir)).getDouble();
        double repeat = jsd.exec(NodeValue.makeNode(hist), NodeValue.makeNode(dir)).getDouble();
        assertEquals(first, repeat, 0.0, "cross-type prob:jsd must be reproducible");
    }

    @Test
    void similarityEvaluatorScoreIsSymmetric() {
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_GT_10K);
        GMMValue p = gaussian1D(0.0, 1.0);
        GMMValue q = gaussian1D(1.5, 2.0);

        double forward = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(p, q);
        double reverse = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(q, p);

        assertEquals(forward, reverse, 0.0,
            "JSD is symmetric; the MC estimate must not depend on argument order");
    }

    @Test
    void mixedCovarianceTypesAreComparableInBothDirections() {
        // Regression: the mixture used to be built with the first operand's covariance
        // layout, which reinterpreted the second operand's entries. That produced a
        // wrong value in one order and an exception in the other.
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_GT_10K);

        GMMValue spherical = new GMMValue(1, 2, "spherical", new double[]{1.0},
            new double[][]{{0.0, 0.0}}, new double[][][]{{{4.0}}});
        GMMValue equivalentFull = new GMMValue(1, 2, "full", new double[]{1.0},
            new double[][]{{0.0, 0.0}}, new double[][][]{{{4.0, 0.0}, {0.0, 4.0}}});
        GMMValue target = new GMMValue(1, 2, "full", new double[]{1.0},
            new double[][]{{0.0, 0.0}}, new double[][][]{{{9.0, 0.0}, {0.0, 0.25}}});

        double mixed = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(spherical, target);
        double swapped = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(target, spherical);
        double reference = new SimilarityEvaluator(0.3, 0.05, 0.05).evaluate(equivalentFull, target);

        assertEquals(mixed, swapped, 0.0, "comparison must be symmetric across covariance layouts");
        // spherical(4) and full(4I) denote the same distribution, so the two estimates
        // must agree to within Monte Carlo error rather than differ structurally.
        assertEquals(reference, mixed, 0.05,
            "a spherical operand must be interpreted as its equivalent full covariance");
    }

    // ---------------------------------------------------------------- validation

    @Test
    void histogramRejectsNonFiniteWeights() {
        assertThrows(IllegalArgumentException.class,
            () -> new HistogramValue(new double[]{0.0, 1.0, 2.0}, new double[]{Double.NaN, 0.5}));
        assertThrows(IllegalArgumentException.class,
            () -> new HistogramValue(new double[]{0.0, 1.0, 2.0},
                new double[]{Double.POSITIVE_INFINITY, 0.5}));
    }

    @Test
    void gmmRejectsNonFiniteParameters() {
        assertThrows(IllegalArgumentException.class, () -> new GMMValue(1, 1, "full",
            new double[]{Double.NaN}, new double[][]{{0.0}}, new double[][][]{{{1.0}}}));
        assertThrows(IllegalArgumentException.class, () -> new GMMValue(1, 1, "full",
            new double[]{1.0}, new double[][]{{Double.NaN}}, new double[][][]{{{1.0}}}));
        assertThrows(IllegalArgumentException.class, () -> new GMMValue(1, 1, "full",
            new double[]{1.0}, new double[][]{{0.0}}, new double[][][]{{{Double.NaN}}}));
        assertThrows(IllegalArgumentException.class, () -> new GMMValue(1, 2, "diag",
            new double[]{1.0}, new double[][]{{0.0, 0.0}}, new double[][][]{{{1.0, Double.NaN}}}));
    }

    @Test
    void dirichletRejectsNonFiniteAlphas() {
        assertThrows(IllegalArgumentException.class,
            () -> new DirichletValue(new double[]{Double.NaN, 1.0}));
        assertThrows(IllegalArgumentException.class,
            () -> new DirichletValue(new double[]{Double.POSITIVE_INFINITY, 1.0}));
    }

    // ------------------------------------------------------------------ helpers

    private static GMMValue gaussian1D(double mean, double variance) {
        return new GMMValue(1, 1, "full", new double[]{1.0},
            new double[][]{{mean}}, new double[][][]{{{variance}}});
    }

    private static Node gmmNode(GMMValue gmm) {
        return NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
    }

    private static Node histogramNode(double[] edges, double[] weights) {
        return NodeFactory.createLiteralDT(new HistogramValue(edges, weights).toString(),
            HistogramDatatype.INSTANCE);
    }

    private static Node dirichletNode(double[] alphas) {
        return NodeFactory.createLiteralDT(new DirichletValue(alphas).toJSON(),
            DirichletDatatype.INSTANCE);
    }
}
