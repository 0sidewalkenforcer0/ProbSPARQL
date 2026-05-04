package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:cdf} — polymorphic CDF evaluation.
 *
 * <p>Dispatches to the appropriate implementation based on the RDF datatype
 * of the first argument:</p>
 * <ul>
 *   <li>{@code uq:gmmLiteral}       → weighted sum of Gaussian CDFs</li>
 *   <li>{@code uq:histLiteral}      → linear-interpolated histogram CDF</li>
 *   <li>{@code uq:dirichletLiteral} → marginal Beta CDF of dimension 0</li>
 * </ul>
 *
 * <p>The legacy type-specific aliases ({@code prob:histcdf}) continue to work
 * via {@link HistogramCDF}.</p>
 */
public class CDF extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#cdf";

    private static final double SQRT_2    = Math.sqrt(2.0);
    private static final int    MC_SAMPLES = 50000;
    private static final java.util.Random random = new java.util.Random(42);

    @Override
    public NodeValue exec(NodeValue distNode, NodeValue pointNode) {
        if (!distNode.isLiteral())
            throw new IllegalArgumentException("First argument of prob:cdf must be a distribution literal");

        String dtype = distNode.asNode().getLiteralDatatypeURI();

        if (GMMDatatype.URI.equals(dtype)) {
            // --- GMM path (original implementation) ---
            GMMValue gmm = extractGMM(distNode);
            double[] point = extractPoint(pointNode, gmm.getDimensions());
            return NodeValue.makeDouble(computeCDF(gmm, point));
        }

        if (HistogramDatatype.URI.equals(dtype)) {
            // --- Histogram path ---
            HistogramValue hist = HistogramJSD.extractHistogram(distNode, "first");
            double[] point = extractHistogramPoint(pointNode, hist.getDimensions());
            return NodeValue.makeDouble(hist.cdf(point));
        }

        if (DirichletDatatype.URI.equals(dtype)) {
            // --- Dirichlet path: marginal CDF of dimension 0 ---
            DirichletValue dir = extractDirichlet(distNode);
            if (!pointNode.isNumber())
                throw new IllegalArgumentException("prob:cdf: second argument must be numeric for Dirichlet");
            return NodeValue.makeDouble(dir.marginalCdf(pointNode.getDouble(), 0));
        }

        throw new IllegalArgumentException(
                "prob:cdf: unsupported distribution type: " + dtype);
    }

    // -----------------------------------------------------------------------
    // Extraction helpers
    // -----------------------------------------------------------------------

    private GMMValue extractGMM(NodeValue node) {
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue))
            throw new IllegalArgumentException(
                    "First argument must be of type " + GMMDatatype.URI);
        return (GMMValue) value;
    }

    private DirichletValue extractDirichlet(NodeValue node) {
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof DirichletValue))
            throw new IllegalArgumentException(
                    "First argument must be of type " + DirichletDatatype.URI);
        return (DirichletValue) value;
    }

    private double[] extractPoint(NodeValue node, int d) {
        if (d == 1) {
            if (node.isNumber())  return new double[]{ node.getDouble() };
            if (node.isString())  return parseVector(node.getString(), d);
            throw new IllegalArgumentException("Second argument must be a number for 1D GMM");
        }
        if (!node.isString())
            throw new IllegalArgumentException("For " + d + "D GMM, second argument must be JSON array");
        return parseVector(node.getString(), d);
    }

    private double[] parseVector(String str, int d) {
        str = str.trim();
        if (!str.startsWith("[") || !str.endsWith("]"))
            throw new IllegalArgumentException("Vector must be JSON array: [v1, ...]");
        String content = str.substring(1, str.length() - 1).trim();
        if (content.isEmpty()) throw new IllegalArgumentException("Empty vector");
        String[] parts = content.split(",");
        if (parts.length != d)
            throw new IllegalArgumentException("Dimension mismatch: expected " + d + " got " + parts.length);
        double[] vector = new double[d];
        for (int i = 0; i < d; i++) vector[i] = Double.parseDouble(parts[i].trim());
        return vector;
    }

    private double[] extractHistogramPoint(NodeValue node, int d) {
        if (d == 1) {
            if (node.isNumber()) return new double[]{node.getDouble()};
            if (node.isString()) return parseVector(node.getString(), d);
            throw new IllegalArgumentException("prob:cdf: second argument must be numeric or JSON array for 1D histogram");
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(
                "prob:cdf: second argument must be a JSON array string for " + d + "D histogram");
        }
        return parseVector(node.getString(), d);
    }

    // -----------------------------------------------------------------------
    // GMM CDF computation (unchanged from original)
    // -----------------------------------------------------------------------

    private double computeCDF(GMMValue gmm, double[] point) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        double cdfValue = 0.0;
        for (int k = 0; k < K; k++) {
            double componentCDF;
            if (d == 1) {
                componentCDF = evaluateGaussianCDF_1D(
                        point[0], means[k][0], getVariance(covariances[k], covType));
            } else if (covType.equals("diag") || covType.equals("spherical")) {
                componentCDF = evaluateGaussianCDF_Diagonal(
                        point, means[k], covariances[k], covType, d);
            } else {
                componentCDF = evaluateGaussianCDF_MonteCarlo(point, means[k], covariances[k], d);
            }
            cdfValue += weights[k] * componentCDF;
        }
        return cdfValue;
    }

    private double getVariance(double[][] cov, String covType) {
        return cov[0][0];
    }

    private double evaluateGaussianCDF_1D(double x, double mu, double sigmaSquared) {
        double sigma = Math.sqrt(sigmaSquared);
        return 0.5 * (1.0 + erf((x - mu) / (sigma * SQRT_2)));
    }

    private double evaluateGaussianCDF_Diagonal(double[] point, double[] mu,
                                                 double[][] cov, String covType, int d) {
        double cdf = 1.0;
        for (int j = 0; j < d; j++) {
            double var = covType.equals("spherical") ? cov[0][0] : cov[0][j];
            cdf *= evaluateGaussianCDF_1D(point[j], mu[j], var);
        }
        return cdf;
    }

    private double evaluateGaussianCDF_MonteCarlo(double[] point, double[] mu,
                                                   double[][] cov, int d) {
        int count = 0;
        double[][] L = choleskyDecompose(cov, d);
        for (int i = 0; i < MC_SAMPLES; i++) {
            double[] sample = sampleGaussian(mu, L, d);
            boolean inside = true;
            for (int j = 0; j < d; j++) {
                if (sample[j] > point[j]) { inside = false; break; }
            }
            if (inside) count++;
        }
        return (double) count / MC_SAMPLES;
    }

    private double[] sampleGaussian(double[] mu, double[][] L, int d) {
        double[] z = new double[d];
        for (int j = 0; j < d; j++) z[j] = random.nextGaussian();
        double[] x = new double[d];
        for (int i = 0; i < d; i++) {
            x[i] = mu[i];
            for (int j = 0; j <= i; j++) x[i] += L[i][j] * z[j];
        }
        return x;
    }

    private double[][] choleskyDecompose(double[][] A, int d) {
        double[][] L = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double s = A[i][j];
                for (int k = 0; k < j; k++) s -= L[i][k] * L[j][k];
                L[i][j] = (i == j) ? Math.sqrt(Math.max(s, 1e-300)) : s / Math.max(L[j][j], 1e-300);
            }
        }
        return L;
    }

    private double erf(double x) {
        double t = 1.0 / (1.0 + 0.5 * Math.abs(x));
        double tau = t * Math.exp(-x * x - 1.26551223
                + t * (1.00002368 + t * (0.37409196
                + t * (0.09678418 + t * (-0.18628806
                + t * (0.27886807 + t * (-1.13520398
                + t * (1.48851587 + t * (-0.82215223
                + t * 0.17087294)))))))));
        return x >= 0.0 ? 1.0 - tau : tau - 1.0;
    }
}

/**
 * SPARQL function to evaluate the Cumulative Distribution Function (CDF) 
 * of a Gaussian Mixture Model at a given point.
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?probability WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:cdf(?gmm, 6.0) AS ?probability)
 * }
 * </pre>
 * 
 * <p>For a GMM with K components in d dimensions:</p>
 * <pre>
 * CDF(x) = Σ(k=1 to K) w_k * Φ(x | μ_k, Σ_k)
 * </pre>
 * where Φ(x | μ, Σ) is the multivariate Gaussian CDF.
 * 
 * @author ProbSPARQL Team
 */
