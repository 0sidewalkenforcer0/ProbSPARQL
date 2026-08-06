package org.apache.jena.probsparql.functions;

import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.sparql.expr.NodeValue;

/**
 * Fixtures and accessors shared by the per-function correctness suites.
 *
 * <p>These tests check each function against a value derived independently — a closed
 * form, an exact discrete sum, or a hand-computed constant — rather than against
 * another code path in the same project, so that a shared mistake cannot make a test
 * agree with the implementation it is meant to check.</p>
 */
final class FunctionTestSupport {

    /** 1/sqrt(2*pi), the density of the standard normal at its mean. */
    static final double STD_NORMAL_PEAK = 0.3989422804014327;
    /** -0.5*ln(2*pi), the log-density of the standard normal at its mean. */
    static final double STD_NORMAL_PEAK_LOG = -0.9189385332046727;
    /** ln 2, the maximum attainable Jensen-Shannon divergence in nats. */
    static final double LN2 = 0.6931471805599453;

    /** Tolerance for values that are computed in closed form. */
    static final double EXACT = 1e-9;
    /** Tolerance for values that are estimated by Monte Carlo at the default budget. */
    static final double MONTE_CARLO = 0.05;

    private FunctionTestSupport() {
    }

    // ------------------------------------------------------------ constructors

    /** Single-component 1-D Gaussian N(mean, variance). */
    static GMMValue gaussian(double mean, double variance) {
        return new GMMValue(1, 1, "full", new double[]{1.0},
            new double[][]{{mean}}, new double[][][]{{{variance}}});
    }

    /** Two-component 1-D mixture w*N(m1,v1) + (1-w)*N(m2,v2). */
    static GMMValue mixture(double w, double m1, double v1, double m2, double v2) {
        return new GMMValue(2, 1, "full", new double[]{w, 1.0 - w},
            new double[][]{{m1}, {m2}}, new double[][][]{{{v1}}, {{v2}}});
    }

    /** Single-component 2-D Gaussian with diagonal covariance, stored as "full". */
    static GMMValue gaussian2D(double m0, double m1, double v0, double v1) {
        return new GMMValue(1, 2, "full", new double[]{1.0},
            new double[][]{{m0, m1}}, new double[][][]{{{v0, 0.0}, {0.0, v1}}});
    }

    /** Single-component 2-D Gaussian in the compact "diag" layout. */
    static GMMValue gaussian2DDiag(double m0, double m1, double v0, double v1) {
        return new GMMValue(1, 2, "diag", new double[]{1.0},
            new double[][]{{m0, m1}}, new double[][][]{{{v0, v1}}});
    }

    static HistogramValue histogram(double[] edges, double[] weights) {
        return new HistogramValue(edges, weights);
    }

    /** Uniform 1-D histogram with {@code bins} equal cells spanning [lo, hi]. */
    static HistogramValue uniformHistogram(double lo, double hi, int bins) {
        double[] edges = new double[bins + 1];
        for (int i = 0; i <= bins; i++) {
            edges[i] = lo + (hi - lo) * i / bins;
        }
        double[] weights = new double[bins];
        java.util.Arrays.fill(weights, 1.0 / bins);
        return new HistogramValue(edges, weights);
    }

    static DirichletValue dirichlet(double... alphas) {
        return new DirichletValue(alphas);
    }

    // ------------------------------------------------------------ node wrapping

    static NodeValue nv(GMMValue gmm) {
        return NodeValue.makeNode(node(gmm));
    }

    static NodeValue nv(HistogramValue histogram) {
        return NodeValue.makeNode(node(histogram));
    }

    static NodeValue nv(DirichletValue dirichlet) {
        return NodeValue.makeNode(node(dirichlet));
    }

    static Node node(GMMValue gmm) {
        return NodeFactory.createLiteralDT(gmm.toJSON(), GMMDatatype.INSTANCE);
    }

    static Node node(HistogramValue histogram) {
        return NodeFactory.createLiteralDT(histogram.toString(), HistogramDatatype.INSTANCE);
    }

    static Node node(DirichletValue dirichlet) {
        return NodeFactory.createLiteralDT(dirichlet.toJSON(), DirichletDatatype.INSTANCE);
    }

    static NodeValue num(double value) {
        return NodeValue.makeDouble(value);
    }

    /**
     * An {@code xsd:integer} literal. Dimension indices must be integers — a SPARQL
     * query writing {@code prob:marginal(?g, 0)} yields this type, whereas
     * {@link #num(double)} yields {@code xsd:double} and is correctly rejected.
     */
    static NodeValue intNum(int value) {
        return NodeValue.makeInteger(value);
    }

    static NodeValue str(String value) {
        return NodeValue.makeString(value);
    }

    // ------------------------------------------------------------ result parsing

    /** Parse a GMM literal returned by a transformation function. */
    static GMMValue asGMM(NodeValue result) {
        return (GMMValue) result.asNode().getLiteralValue();
    }

    /** Parse a histogram literal returned by a transformation function. */
    static HistogramValue asHistogram(NodeValue result) {
        return (HistogramValue) result.asNode().getLiteralValue();
    }

    /**
     * Parse the {@code "[1.000000, 2.000000]"} vector string returned by
     * {@code prob:mean}, {@code prob:std} and {@code prob:map}.
     */
    static double[] asVector(NodeValue result) {
        String text = result.getString().trim();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            throw new IllegalArgumentException("Not a vector literal: " + text);
        }
        String body = text.substring(1, text.length() - 1).trim();
        if (body.isEmpty()) {
            return new double[0];
        }
        String[] parts = body.split(",");
        double[] values = new double[parts.length];
        for (int i = 0; i < parts.length; i++) {
            values[i] = Double.parseDouble(parts[i].trim());
        }
        return values;
    }

    /** Parse the {@code "[[x],[y]]"} sample matrix returned by {@code prob:sample}. */
    static double[][] asMatrix(NodeValue result) {
        String text = result.getString().trim();
        if (!text.startsWith("[[") || !text.endsWith("]]")) {
            throw new IllegalArgumentException("Not a sample matrix: " + text);
        }
        String body = text.substring(1, text.length() - 1).trim();
        String[] rows = body.split("\\]\\s*,\\s*\\[");
        double[][] matrix = new double[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].replace("[", "").replace("]", "").trim();
            String[] parts = row.split(",");
            matrix[i] = new double[parts.length];
            for (int j = 0; j < parts.length; j++) {
                matrix[i][j] = Double.parseDouble(parts[j].trim());
            }
        }
        return matrix;
    }

    // ------------------------------------------------------------ reference maths

    /** Density of N(mean, variance) at x. */
    static double normalPdf(double x, double mean, double variance) {
        double d = x - mean;
        return Math.exp(-0.5 * d * d / variance) / Math.sqrt(2.0 * Math.PI * variance);
    }

    /** CDF of N(mean, variance) at x, via the high-accuracy complementary error function. */
    static double normalCdf(double x, double mean, double variance) {
        return 0.5 * erfc(-(x - mean) / Math.sqrt(2.0 * variance));
    }

    /**
     * Closed-form KL divergence between two 1-D Gaussians:
     * ln(s2/s1) + (v1 + (m1-m2)^2) / (2*v2) - 1/2.
     */
    static double gaussianKL(double m1, double v1, double m2, double v2) {
        return 0.5 * Math.log(v2 / v1) + (v1 + (m1 - m2) * (m1 - m2)) / (2.0 * v2) - 0.5;
    }

    /**
     * Complementary error function, Numerical Recipes' Chebyshev form.
     * Fractional error below 1.2e-7 everywhere, which is ample for these assertions
     * and is computed independently of anything in the main source tree.
     */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 2.0 / (2.0 + z);
        double ty = 4.0 * t - 2.0;
        double[] cof = {
            -1.3026537197817094, 6.4196979235649026e-1, 1.9476473204185836e-2,
            -9.561514786808631e-3, -9.46595344482036e-4, 3.66839497852761e-4,
            4.2523324806907e-5, -2.0278578112534e-5, -1.624290004647e-6,
            1.303655835580e-6, 1.5626441722e-8, -8.5238095915e-8,
            6.529054439e-9, 5.059343495e-9, -9.91364156e-10,
            -2.27365122e-10, 9.6467911e-11, 2.394038e-12,
            -6.886027e-12, 8.94487e-13, 3.13092e-13,
            -1.12708e-13, 3.81e-16, 7.106e-15
        };
        double d = 0.0;
        double dd = 0.0;
        for (int j = cof.length - 1; j > 0; j--) {
            double tmp = d;
            d = ty * d - dd + cof[j];
            dd = tmp;
        }
        double ans = t * Math.exp(-z * z + 0.5 * (cof[0] + ty * d) - dd);
        return x >= 0.0 ? ans : 2.0 - ans;
    }
}
