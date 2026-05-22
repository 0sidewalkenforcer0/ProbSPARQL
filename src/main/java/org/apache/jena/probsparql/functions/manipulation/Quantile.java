package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramOperations;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute univariate quantiles.
 * 
 * <p>For a 1D GMM or Histogram and probability q ∈ [0,1], finds the value x such that:</p>
 * <pre>
 * P(X ≤ x) = q
 * </pre>
 * 
 * <p>GMM and Dirichlet marginal quantiles use numerical root finding. Histogram
 * quantiles use inverse CDF over the piecewise-uniform bins.</p>
 * 
 * <p>For Dirichlet, this returns the marginal quantile of dimension 0. General
 * multivariate quantiles are not uniquely defined and are not supported.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?q95 WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:quantile(?gmm, 0.95) AS ?q95)  # 95th percentile
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Quantile extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#quantile";
    
    private static final double TOLERANCE = 1e-6;
    private static final int MAX_ITERATIONS = 100;
    private static final double SQRT_2PI = Math.sqrt(2.0 * Math.PI);
    
    /**
     * Compute univariate quantile.
     * 
     * @param distNode Distribution literal
     * @param qNode Probability q ∈ [0,1]
     * @return Quantile value x such that P(X ≤ x) = q
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue qNode) {
        double q = extractDouble(qNode, "quantile probability");

        // Validate probability range
        if (q < 0 || q > 1) {
            throw new IllegalArgumentException(
                "Quantile probability must be in [0,1], got: " + q);
        }

        Object value = distNode.asNode().getLiteralValue();
        if (value instanceof HistogramValue histogram) {
            return NodeValue.makeDouble(HistogramOperations.quantile(histogram, q));
        }
        if (value instanceof DirichletValue dirichlet) {
            return NodeValue.makeDouble(computeDirichletMarginalQuantile(dirichlet, q, 0));
        }

        GMMValue gmm = extractGMM(distNode);
        // Validate 1D
        if (gmm.getDimensions() != 1) {
            throw new IllegalArgumentException(
                "Quantile function only supports 1D GMMs. Got d=" + gmm.getDimensions());
        }

        // Handle edge cases
        if (q == 0.0) {
            return NodeValue.makeDouble(Double.NEGATIVE_INFINITY);
        }
        if (q == 1.0) {
            return NodeValue.makeDouble(Double.POSITIVE_INFINITY);
        }
        
        double quantileValue = computeQuantile(gmm, q);
        
        return NodeValue.makeDouble(quantileValue);
    }

    private double computeDirichletMarginalQuantile(DirichletValue dirichlet, double q, int dim) {
        if (q == 0.0) {
            return 0.0;
        }
        if (q == 1.0) {
            return 1.0;
        }
        double lower = 0.0;
        double upper = 1.0;
        for (int i = 0; i < MAX_ITERATIONS; i++) {
            double mid = 0.5 * (lower + upper);
            double cdf = dirichlet.marginalCdf(mid, dim);
            if (Math.abs(cdf - q) < TOLERANCE) {
                return mid;
            }
            if (cdf < q) {
                lower = mid;
            } else {
                upper = mid;
            }
        }
        return 0.5 * (lower + upper);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "Argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "Argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Extract double value from NodeValue.
     */
    private double extractDouble(NodeValue node, String name) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "The " + name + " must be a number");
        }
        return node.getDouble();
    }
    
    /**
     * Compute quantile using bisection method.
     * 
     * Find x such that CDF(x) = q
     */
    private double computeQuantile(GMMValue gmm, double targetQ) {
        // Find reasonable search bounds based on mean ± several standard deviations
        double[] bounds = getSearchBounds(gmm);
        double lower = bounds[0];
        double upper = bounds[1];
        
        // Verify bounds bracket the solution
        double cdfLower = cdf(gmm, lower);
        double cdfUpper = cdf(gmm, upper);
        
        if (cdfLower > targetQ) {
            // Extend lower bound
            while (cdf(gmm, lower) > targetQ && lower > -1e10) {
                lower = lower - (upper - lower);
            }
        }
        
        if (cdfUpper < targetQ) {
            // Extend upper bound
            while (cdf(gmm, upper) < targetQ && upper < 1e10) {
                upper = upper + (upper - lower);
            }
        }
        
        // Bisection search
        for (int iter = 0; iter < MAX_ITERATIONS; iter++) {
            double mid = (lower + upper) / 2.0;
            double cdfMid = cdf(gmm, mid);
            
            if (Math.abs(cdfMid - targetQ) < TOLERANCE) {
                return mid;
            }
            
            if (cdfMid < targetQ) {
                lower = mid;
            } else {
                upper = mid;
            }
        }
        
        // Return best estimate
        return (lower + upper) / 2.0;
    }
    
    /**
     * Get reasonable search bounds as [mean - 6*std, mean + 6*std].
     */
    private double[] getSearchBounds(GMMValue gmm) {
        int K = gmm.getNComponents();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Compute mean
        double mean = 0.0;
        for (int i = 0; i < K; i++) {
            mean += weights[i] * means[i][0];
        }
        
        // Compute variance: E[X²] - E[X]²
        double secondMoment = 0.0;
        for (int i = 0; i < K; i++) {
            double variance;
            switch (covType) {
                case "full":
                    variance = covariances[i][0][0];
                    break;
                case "diag":
                    variance = covariances[i][0][0];
                    break;
                case "spherical":
                    variance = covariances[i][0][0];
                    break;
                default:
                    variance = 1.0;
            }
            secondMoment += weights[i] * (variance + means[i][0] * means[i][0]);
        }
        
        double variance = secondMoment - mean * mean;
        double std = Math.sqrt(Math.max(variance, 0.0));
        
        // Search within 6 standard deviations
        double[] bounds = new double[2];
        bounds[0] = mean - 6 * std;
        bounds[1] = mean + 6 * std;
        
        return bounds;
    }
    
    /**
     * Compute CDF at point x.
     * 
     * CDF(x) = Σ w_i * Φ((x - μ_i) / σ_i)
     */
    private double cdf(GMMValue gmm, double x) {
        int K = gmm.getNComponents();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        double cdfValue = 0.0;
        
        for (int i = 0; i < K; i++) {
            double mu = means[i][0];
            double sigma;
            
            switch (covType) {
                case "full":
                    sigma = Math.sqrt(covariances[i][0][0]);
                    break;
                case "diag":
                    sigma = Math.sqrt(covariances[i][0][0]);
                    break;
                case "spherical":
                    sigma = Math.sqrt(covariances[i][0][0]);
                    break;
                default:
                    sigma = 1.0;
            }
            
            // Standard normal CDF
            double z = (x - mu) / sigma;
            double phi = normalCDF(z);
            
            cdfValue += weights[i] * phi;
        }
        
        return cdfValue;
    }
    
    /**
     * Standard normal CDF using error function approximation.
     * 
     * Φ(x) = 0.5 * (1 + erf(x/√2))
     */
    private double normalCDF(double x) {
        return 0.5 * (1.0 + erf(x / Math.sqrt(2.0)));
    }
    
    /**
     * Error function approximation (Abramowitz and Stegun).
     * 
     * Maximum error: 1.5e-7
     */
    private double erf(double x) {
        // Constants
        double a1 =  0.254829592;
        double a2 = -0.284496736;
        double a3 =  1.421413741;
        double a4 = -1.453152027;
        double a5 =  1.061405429;
        double p  =  0.3275911;
        
        // Save the sign of x
        int sign = (x >= 0) ? 1 : -1;
        x = Math.abs(x);
        
        // A&S formula 7.1.26
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        
        return sign * y;
    }
}
