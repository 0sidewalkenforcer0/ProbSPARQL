package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

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
public class CDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#cdf";
    
    private static final double SQRT_2 = Math.sqrt(2.0);
    private static final int MC_SAMPLES = 50000; // Monte Carlo samples for full covariance
    private static final java.util.Random random = new java.util.Random(42);
    
    /**
     * Evaluate CDF at a point.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return CDF value (cumulative probability) as double
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue pointNode) {
        // Extract GMM from first argument
        GMMValue gmm = extractGMM(gmmNode);
        
        // Extract evaluation point
        double[] point = extractPoint(pointNode, gmm.getD());
        
        // Compute CDF
        double cumulativeProbability = computeCDF(gmm, point);
        
        return NodeValue.makeDouble(cumulativeProbability);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException("First argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "First argument must be of type " + GMMDatatype.URI + 
                ", got: " + node.asNode().getLiteralDatatypeURI());
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Extract evaluation point from NodeValue.
     * For d=1, accepts a single number or JSON array "[x]"
     * For d>1, accepts JSON array "[x1, x2, ..., xd]"
     */
    private double[] extractPoint(NodeValue node, int d) {
        if (d == 1) {
            // For 1D, accept both number and JSON array
            if (node.isNumber()) {
                return new double[] { node.getDouble() };
            } else if (node.isString()) {
                return parseVector(node.getString(), d);
            } else {
                throw new IllegalArgumentException(
                    "Second argument must be a number or JSON array for 1D GMM");
            }
        } else {
            // For multi-D, require JSON array string
            if (!node.isString()) {
                throw new IllegalArgumentException(
                    "For " + d + "D GMM, second argument must be JSON array: \"[x1, x2, ..., xd]\"");
            }
            return parseVector(node.getString(), d);
        }
    }
    
    /**
     * Parse vector from JSON array string: "[v1, v2, ..., vd]"
     */
    private double[] parseVector(String str, int d) {
        str = str.trim();
        if (!str.startsWith("[") || !str.endsWith("]")) {
            throw new IllegalArgumentException(
                "Vector must be JSON array format: [v1, v2, ...]");
        }
        
        String content = str.substring(1, str.length() - 1).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Empty vector not allowed");
        }
        
        String[] parts = content.split(",");
        if (parts.length != d) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + d + ", got " + parts.length);
        }
        
        double[] vector = new double[d];
        for (int i = 0; i < d; i++) {
            try {
                vector[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid number at index " + i + ": " + parts[i].trim(), e);
            }
        }
        
        return vector;
    }
    
    /**
     * Compute CDF value for a GMM at given point.
     * 
     * CDF(x) = Σ(k=1 to K) w_k * Φ(x | μ_k, Σ_k)
     */
    private double computeCDF(GMMValue gmm, double[] point) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        double cdfValue = 0.0;
        
        for (int k = 0; k < K; k++) {
            double componentCDF;
            
            if (d == 1) {
                // 1D: Use analytical formula with erf
                componentCDF = evaluateGaussianCDF_1D(
                    point[0], 
                    means[k][0], 
                    getVariance(covariances[k], covType)
                );
            } else if (covType.equals("diag") || covType.equals("spherical")) {
                // Multi-D with diagonal/spherical: Product of 1D CDFs
                componentCDF = evaluateGaussianCDF_Diagonal(
                    point, means[k], covariances[k], covType, d
                );
            } else {
                // Multi-D with full covariance: Monte Carlo approximation
                componentCDF = evaluateGaussianCDF_MonteCarlo(
                    point, means[k], covariances[k], d
                );
            }
            
            cdfValue += weights[k] * componentCDF;
        }
        
        return cdfValue;
    }
    
    /**
     * Get variance from covariance matrix based on type.
     * For 1D case, extracts the single variance value.
     */
    private double getVariance(double[][] covariance, String covType) {
        switch (covType) {
            case "full":
                return covariance[0][0];
            case "diag":
                return covariance[0][0];
            case "spherical":
                return covariance[0][0];
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
    
    /**
     * Evaluate univariate Gaussian CDF at a point (1D case).
     * 
     * Φ(x | μ, σ²) = 0.5 * (1 + erf((x - μ) / (σ * sqrt(2))))
     */
    private double evaluateGaussianCDF_1D(double x, double mean, double variance) {
        double stddev = Math.sqrt(variance);
        double z = (x - mean) / (stddev * SQRT_2);
        return 0.5 * (1.0 + erf(z));
    }
    
    /**
     * Evaluate multi-dimensional Gaussian CDF for diagonal/spherical covariance.
     * Uses product of marginal CDFs (assumes independence).
     * 
     * P(X₁≤x₁, ..., Xd≤xd) = Π P(Xᵢ≤xᵢ)
     */
    private double evaluateGaussianCDF_Diagonal(double[] point, double[] mean,
                                                 double[][] covariance, String covType, int d) {
        double cdf = 1.0;
        
        for (int i = 0; i < d; i++) {
            double variance;
            if (covType.equals("spherical")) {
                variance = covariance[0][0]; // Same variance for all dimensions
            } else { // "diag"
                variance = covariance[i][0]; // Different variance per dimension (fixed bug: was covariance[0][i])
            }
            
            double marginalCDF = evaluateGaussianCDF_1D(point[i], mean[i], variance);
            cdf *= marginalCDF;
        }
        
        return cdf;
    }
    
    /**
     * Evaluate multi-dimensional Gaussian CDF using Monte Carlo sampling.
     * Used for full covariance matrices where dimensions are correlated.
     * 
     * P(X≤x) ≈ (# samples where all xᵢ ≤ pointᵢ) / total_samples
     */
    private double evaluateGaussianCDF_MonteCarlo(double[] point, double[] mean,
                                                   double[][] covariance, int d) {
        int count = 0;
        
        // Cholesky decomposition for sampling
        double[][] L = MatrixUtils.choleskyDecomposition(covariance, d);
        
        for (int s = 0; s < MC_SAMPLES; s++) {
            // Sample from standard normal
            double[] z = new double[d];
            for (int i = 0; i < d; i++) {
                z[i] = random.nextGaussian();
            }
            
            // Transform: x = μ + L*z
            double[] sample = new double[d];
            for (int i = 0; i < d; i++) {
                sample[i] = mean[i];
                for (int j = 0; j < d; j++) {
                    sample[i] += L[i][j] * z[j];
                }
            }
            
            // Check if sample ≤ point in all dimensions
            boolean allLessOrEqual = true;
            for (int i = 0; i < d; i++) {
                if (sample[i] > point[i]) {
                    allLessOrEqual = false;
                    break;
                }
            }
            
            if (allLessOrEqual) {
                count++;
            }
        }
        
        return (double) count / MC_SAMPLES;
    }
    
    /**
     * Error function approximation using Abramowitz and Stegun formula.
     * 
     * Maximum error: 1.5e-7
     */
    private double erf(double x) {
        // Constants for approximation
        double a1 =  0.254829592;
        double a2 = -0.284496736;
        double a3 =  1.421413741;
        double a4 = -1.453152027;
        double a5 =  1.061405429;
        double p  =  0.3275911;
        
        // Save the sign of x
        int sign = (x < 0) ? -1 : 1;
        x = Math.abs(x);
        
        // A&S formula 7.1.26
        double t = 1.0 / (1.0 + p * x);
        double y = 1.0 - (((((a5 * t + a4) * t) + a3) * t + a2) * t + a1) * t * Math.exp(-x * x);
        
        return sign * y;
    }
}
