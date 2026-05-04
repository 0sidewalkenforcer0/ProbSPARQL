package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:jsd} — polymorphic Jensen-Shannon divergence.
 *
 * <p>Dispatches to type-optimised implementations for same-type pairs and
 * falls back to a universal sample-based estimator for cross-type pairs:</p>
 *
 * <table border="1" cellpadding="4">
 *   <tr><th>Type pair</th><th>Algorithm</th><th>Complexity</th></tr>
 *   <tr><td>GMM ↔ GMM</td><td>MC sampling (GT_10K)</td><td>O(N × K)</td></tr>
 *   <tr><td>Hist ↔ Hist</td><td>Exact discrete KL summation</td><td>O(N)</td></tr>
 *   <tr><td>Dir ↔ Dir</td><td>MC sampling from Dirichlet</td><td>O(N × k)</td></tr>
 *   <tr><td>Cross-type</td><td>Sample-based fallback</td><td>O(N)</td></tr>
 * </table>
 *
 * <p>Note: the legacy function {@code prob:jsdivergence} (GMM-only, with
 * configurable sampling mode) is preserved unchanged. This function always
 * uses N=10,000 samples for MC paths.</p>
 */
public class PolyJSD extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#jsd";

    /** Sample count for MC-based JSD (Dir↔Dir, cross-type). */
    private static final int N_SAMPLES = 10_000;

    // -----------------------------------------------------------------------
    // Main dispatch
    // -----------------------------------------------------------------------

    @Override
    public NodeValue exec(NodeValue d1Node, NodeValue d2Node) {
        if (!d1Node.isLiteral() || !d2Node.isLiteral())
            throw new IllegalArgumentException("prob:jsd: both arguments must be distribution literals");

        String type1 = d1Node.asNode().getLiteralDatatypeURI();
        String type2 = d2Node.asNode().getLiteralDatatypeURI();

        // --- Same-type: optimised paths ---
        if (GMMDatatype.URI.equals(type1) && GMMDatatype.URI.equals(type2)) {
            GMMValue g1 = extractGMM(d1Node, "first");
            GMMValue g2 = extractGMM(d2Node, "second");
            if (g1.getDimensions() != g2.getDimensions())
                throw new IllegalArgumentException(
                        "prob:jsd: GMM dimensionality mismatch: d1=" + g1.getDimensions() + " d2=" + g2.getDimensions());
            return NodeValue.makeDouble(gmmJSD(g1, g2));
        }

        if (HistogramDatatype.URI.equals(type1) && HistogramDatatype.URI.equals(type2)) {
            HistogramValue h1 = extractHistogram(d1Node, "first");
            HistogramValue h2 = extractHistogram(d2Node, "second");
            if (!h1.isCompatible(h2))
                throw new IllegalArgumentException(
                        "prob:jsd: histograms must have the same dimensional grid");
            return NodeValue.makeDouble(HistogramJSD.computeJSD(h1.probabilities(), h2.probabilities()));
        }

        if (DirichletDatatype.URI.equals(type1) && DirichletDatatype.URI.equals(type2)) {
            DirichletValue dir1 = extractDirichlet(d1Node, "first");
            DirichletValue dir2 = extractDirichlet(d2Node, "second");
            if (dir1.getDimensions() != dir2.getDimensions())
                throw new IllegalArgumentException(
                        "prob:jsd: Dirichlet dimension mismatch: dim1=" + dir1.getDimensions()
                                + " dim2=" + dir2.getDimensions());
            return NodeValue.makeDouble(sampleBasedJSD(dir1, dir2, N_SAMPLES));
        }

        // --- Cross-type: universal sample-based fallback ---
        Sampleable s1 = extractSampleable(d1Node, "first");
        Sampleable s2 = extractSampleable(d2Node, "second");
        if (sampleableDimensions(s1) != sampleableDimensions(s2))
            throw new IllegalArgumentException(
                    "prob:jsd: distribution dimension mismatch: d1=" + sampleableDimensions(s1)
                            + " d2=" + sampleableDimensions(s2));
        return NodeValue.makeDouble(sampleBasedJSD(s1, s2, N_SAMPLES));
    }

    // -----------------------------------------------------------------------
    // GMM ↔ GMM (Monte Carlo, GT_10K)
    // -----------------------------------------------------------------------

    private double gmmJSD(GMMValue p, GMMValue q) {
        // Create mixture M = 0.5*P + 0.5*Q
        double jsd = 0.5 * mcKL(p, q, p, N_SAMPLES / 2) + 0.5 * mcKL(q, p, q, N_SAMPLES / 2);
        return Math.max(0.0, jsd);
    }

    /**
     * KL(from ‖ to+from mixture) estimated by sampling {@code n} points from {@code from}.
     * Uses the sample-based approach: KL(P‖M) ≈ E_P[log P(x) - log M(x)].
     */
    private double mcKL(GMMValue from, GMMValue other, GMMValue fromAgain, int n) {
        double[][] samples = from.sample(n);
        double sum = 0.0;
        for (double[] x : samples) {
            double logP = from.logPdf(x);
            double logQ = other.logPdf(x);
            // log M(x) = log(0.5*P(x) + 0.5*Q(x)) = log(0.5) + log(exp(logP) + exp(logQ))
            double logM = Math.log(0.5) + logSumExp(logP, logQ);
            sum += logP - logM;
        }
        return sum / n;
    }

    // -----------------------------------------------------------------------
    // Universal sample-based JSD fallback
    // -----------------------------------------------------------------------

    /**
     * Estimates JSD(s1 ‖ s2) using pooled samples.
     *
     * <p>Algorithm:</p>
     * <ol>
     *   <li>Draw n/2 samples from s1, n/2 from s2, pool them.</li>
     *   <li>Evaluate log-density at each pooled sample under both distributions.</li>
     *   <li>JSD ≈ ½ E_{s1}[log s1(x) − log M(x)] + ½ E_{s2}[log s2(x) − log M(x)]</li>
     * </ol>
     */
    public static double sampleBasedJSD(Sampleable s1, Sampleable s2, int n) {
        int half = n / 2;
        double[][] samples1 = s1.sample(half);
        double[][] samples2 = s2.sample(half);

        double klS1M = 0.0;
        for (double[] x : samples1) {
            double logP = s1.logPdf(x);
            double logQ = s2.logPdf(x);
            if (Double.isInfinite(logP)) continue;
            double logM = Math.log(0.5) + logSumExp(logP, Double.isInfinite(logQ) ? logP - 700 : logQ);
            klS1M += logP - logM;
        }
        klS1M /= half;

        double klS2M = 0.0;
        for (double[] x : samples2) {
            double logP = s1.logPdf(x);
            double logQ = s2.logPdf(x);
            if (Double.isInfinite(logQ)) continue;
            double logM = Math.log(0.5) + logSumExp(Double.isInfinite(logP) ? logQ - 700 : logP, logQ);
            klS2M += logQ - logM;
        }
        klS2M /= half;

        return Math.max(0.0, 0.5 * klS1M + 0.5 * klS2M);
    }

    private static double logSumExp(double a, double b) {
        double max = Math.max(a, b);
        if (Double.isInfinite(max)) return max;
        return max + Math.log(Math.exp(a - max) + Math.exp(b - max));
    }

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    private GMMValue extractGMM(NodeValue node, String pos) {
        Object v = node.asNode().getLiteralValue();
        if (!(v instanceof GMMValue))
            throw new IllegalArgumentException("prob:jsd: " + pos + " argument must be a gmmLiteral");
        return (GMMValue) v;
    }

    private HistogramValue extractHistogram(NodeValue node, String pos) {
        return HistogramJSD.extractHistogram(node, pos);
    }

    private DirichletValue extractDirichlet(NodeValue node, String pos) {
        Object v = node.asNode().getLiteralValue();
        if (!(v instanceof DirichletValue))
            throw new IllegalArgumentException("prob:jsd: " + pos + " argument must be a dirichletLiteral");
        return (DirichletValue) v;
    }

    private Sampleable extractSampleable(NodeValue node, String pos) {
        Object v = node.asNode().getLiteralValue();
        if (v instanceof Sampleable s) return s;
        throw new IllegalArgumentException(
                "prob:jsd: " + pos + " argument has unsupported distribution type: "
                + node.asNode().getLiteralDatatypeURI());
    }

    private int sampleableDimensions(Sampleable value) {
        if (value instanceof GMMValue gmm) return gmm.getDimensions();
        if (value instanceof HistogramValue histogram) return histogram.getDimensions();
        if (value instanceof DirichletValue dirichlet) return dirichlet.getDimensions();
        throw new IllegalArgumentException("Unsupported Sampleable implementation: " + value.getClass().getName());
    }
}
