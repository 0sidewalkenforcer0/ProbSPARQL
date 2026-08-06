package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.probsparql.functions.DistributionSeeds;
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

    /**
     * Log-density gap used to stand in for a zero density on disjoint support.
     * Large enough that {@code exp(-gap)} underflows to zero, so the mixture term
     * reduces to {@code ½·S(x)} and the integrand attains its {@code log 2} limit,
     * yet finite so {@code logSumExp} stays well-conditioned.
     */
    private static final double DISJOINT_SUPPORT_LOG_GAP = 700.0;

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

        if (DirichletDatatype.URI.equals(type1) && HistogramDatatype.URI.equals(type2)) {
            DirichletValue dir = extractDirichlet(d1Node, "first");
            HistogramValue hist = extractHistogram(d2Node, "second");
            if (hist.getDimensions() == 1) {
                return NodeValue.makeDouble(sampleBasedJSD(new DirichletMarginal(dir, 0), hist, N_SAMPLES));
            }
        }

        if (HistogramDatatype.URI.equals(type1) && DirichletDatatype.URI.equals(type2)) {
            HistogramValue hist = extractHistogram(d1Node, "first");
            DirichletValue dir = extractDirichlet(d2Node, "second");
            if (hist.getDimensions() == 1) {
                return NodeValue.makeDouble(sampleBasedJSD(hist, new DirichletMarginal(dir, 0), N_SAMPLES));
            }
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
        // JSD is symmetric, so evaluate the operands in a canonical order: otherwise
        // swapping the arguments would consume the shared stream differently and
        // return a different realisation of the same quantity.
        GMMValue first = p;
        GMMValue second = q;
        if (!DistributionSeeds.inCanonicalOrder(p, q)) {
            first = q;
            second = p;
        }
        java.util.Random rng = DistributionSeeds.rngForPair(first, second);
        int half = N_SAMPLES / 2;
        double jsd = 0.5 * mcKL(first, second, half, rng) + 0.5 * mcKL(second, first, half, rng);
        return Math.max(0.0, Math.min(jsd, Math.log(2.0)));
    }

    /**
     * KL(from ‖ M) with M = ½(from + other), estimated by sampling {@code n} points
     * from {@code from}: KL(P‖M) ≈ E_P[log P(x) - log M(x)].
     */
    private double mcKL(GMMValue from, GMMValue other, int n, java.util.Random rng) {
        double[][] samples = from.sample(n, rng);
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
        // JSD is symmetric, and 0.5*KL(s1||M) + 0.5*KL(s2||M) is symmetric in the two
        // operands, but they consume the shared random stream in argument order. Fixing
        // that order makes the estimate itself order-independent, not merely unbiased.
        if (!DistributionSeeds.inCanonicalOrder(s1, s2)) {
            Sampleable swap = s1;
            s1 = s2;
            s2 = swap;
        }
        return sampleBasedJSD(s1, s2, n, DistributionSeeds.rngForPair(s1, s2));
    }

    /**
     * As {@link #sampleBasedJSD(Sampleable, Sampleable, int)}, with an explicit random
     * source so the estimate is reproducible.
     *
     * <p>This overload samples {@code s1} before {@code s2}, so a caller that needs
     * {@code f(a,b) == f(b,a)} must fix the operand order itself; the single-argument
     * form above does that via {@link DistributionSeeds#inCanonicalOrder}.</p>
     */
    public static double sampleBasedJSD(Sampleable s1, Sampleable s2, int n, java.util.Random rng) {
        int half = n / 2;
        double[][] samples1 = s1.sample(half, rng);
        double[][] samples2 = s2.sample(half, rng);

        double klS1M = averageLogRatio(s1, s2, samples1, true);
        double klS2M = averageLogRatio(s1, s2, samples2, false);

        return Math.max(0.0, Math.min(0.5 * klS1M + 0.5 * klS2M, Math.log(2.0)));
    }

    /**
     * Monte Carlo estimate of KL(S‖M) where S is {@code s1} when {@code fromFirst} and
     * {@code s2} otherwise, and M = ½(s1 + s2).
     *
     * <p>Samples at which the sampled distribution's own density is zero cannot occur
     * except through numerical underflow; they are skipped and excluded from the
     * divisor, since averaging over the nominal sample count instead would bias the
     * estimate low by the fraction skipped.</p>
     *
     * <p>Where the <em>other</em> density is zero the mixture is locally
     * {@code ½·S(x)}, so the integrand equals {@code log 2}. Substituting a
     * far-smaller finite log-density reproduces that limit while keeping
     * {@code logSumExp} well-conditioned.</p>
     */
    private static double averageLogRatio(Sampleable s1, Sampleable s2, double[][] samples, boolean fromFirst) {
        double sum = 0.0;
        int used = 0;
        for (double[] x : samples) {
            double logP = s1.logPdf(x);
            double logQ = s2.logPdf(x);
            double logOwn = fromFirst ? logP : logQ;
            double logOther = fromFirst ? logQ : logP;
            if (Double.isInfinite(logOwn) || Double.isNaN(logOwn)) continue;
            double logOtherEffective = Double.isInfinite(logOther) || Double.isNaN(logOther)
                ? logOwn - DISJOINT_SUPPORT_LOG_GAP
                : logOther;
            double logM = Math.log(0.5) + logSumExp(logOwn, logOtherEffective);
            sum += logOwn - logM;
            used++;
        }
        return used == 0 ? 0.0 : sum / used;
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
        if (value instanceof DirichletMarginal) return 1;
        throw new IllegalArgumentException("Unsupported Sampleable implementation: " + value.getClass().getName());
    }

    private static final class DirichletMarginal implements Sampleable {
        private final DirichletValue dirichlet;
        private final int dim;

        private DirichletMarginal(DirichletValue dirichlet, int dim) {
            this.dirichlet = dirichlet;
            this.dim = dim;
        }

        @Override
        public double[][] sample(int n) {
            return dirichlet.sampleMarginal(n, dim);
        }

        @Override
        public double[][] sample(int n, java.util.Random rng) {
            return dirichlet.sampleMarginal(n, dim, rng);
        }

        @Override
        public int hashCode() {
            return 31 * dirichlet.hashCode() + dim;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof DirichletMarginal other
                && dim == other.dim
                && dirichlet.equals(other.dirichlet);
        }

        @Override
        public double logPdf(double[] x) {
            if (x.length != 1) {
                throw new IllegalArgumentException("Dirichlet marginal expects one-dimensional samples");
            }
            return dirichlet.marginalLogPdf(x[0], dim);
        }
    }
}
