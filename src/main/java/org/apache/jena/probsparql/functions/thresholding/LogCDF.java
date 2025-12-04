package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the logarithm of Cumulative Distribution Function (Log-CDF) 
 * of a Gaussian Mixture Model at a given point.
 * 
 * <p>Log-CDF is numerically more stable than CDF for very small probabilities.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?logProbability WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:logcdf(?gmm, 6.0) AS ?logProbability)
 * }
 * </pre>
 * 
 * <p>For a GMM with K components:</p>
 * <pre>
 * log(CDF(x)) = log(Σ(k=1 to K) w_k * Φ(x | μ_k, Σ_k))
 * </pre>
 * 
 * Uses log-sum-exp trick for numerical stability.
 * 
 * @author ProbSPARQL Team
 */
public class LogCDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#logcdf";
    
    private static final double SQRT_2 = Math.sqrt(2.0);
    private static final int MC_SAMPLES = 50000;
    private static final java.util.Random random = new java.util.Random(42);
    
    /**
     * Evaluate Log-CDF at a point.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return Log-CDF value as double
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue pointNode) {
        // Extract GMM from first argument
        GMMValue gmm = extractGMM(gmmNode);
        
        // Extract evaluation point
        double[] point = extractPoint(pointNode, gmm.getD());
        
        // Compute Log-CDF
        double logCumulativeProbability = computeLogCDF(gmm, point);
        
        return NodeValue.makeDouble(logCumulativeProbability);
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
            if (node.isNumber()) {
                return new double[] { node.getDouble() };
            } else if (node.isString()) {
                return parseVector(node.getString(), d);
            } else {
                throw new IllegalArgumentException(
                    "Second argument must be a number or JSON array for 1D GMM");
            }
        } else {
            if (!node.isString()) {
                throw new IllegalArgumentException(
                    "For " + d + "D GMM, second argument must be JSON array: \"[x1, x2, ..., xd]\"");
            }
            return parseVector(node.getString(), d);
        }
    }
    
    /**
     * Parse vector from JSON array string.
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
     * Compute Log-CDF value for a GMM at given point.
     * 
     * Uses log-sum-exp trick for numerical stability.
     */
    private double computeLogCDF(GMMValue gmm, double[] point) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Compute log(w_k) + log(Φ(x | μ_k, Σ_k)) for each component
        double[] logComponents = new double[K];
        
        for (int k = 0; k < K; k++) {
            double logWeight = Math.log(weights[k]);
            double cdf;
            
            if (d == 1) {
                cdf = evaluateGaussianCDF_1D(
                    point[0], means[k][0], getVariance(covariances[k], covType)
                );
            } else if (covType.equals("diag") || covType.equals("spherical")) {
                cdf = evaluateGaussianCDF_Diagonal(
                    point, means[k], covariances[k], covType, d
                );
            } else {
                cdf = evaluateGaussianCDF_MonteCarlo(
                    point, means[k], covariances[k], d
                );
            }
            
            // Handle edge cases
            if (cdf <= 0.0) {
                logComponents[k] = Double.NEGATIVE_INFINITY;
            } else if (cdf >= 1.0) {
                logComponents[k] = logWeight;
            } else {
                logComponents[k] = logWeight + Math.log(cdf);
            }
        }
        
        // Use log-sum-exp trick
        return logSumExp(logComponents);
    }
    
    /**
     * Get variance from covariance matrix based on type.
     */
    private double getVariance(double[][] covariance, String covType) {
        switch (covType) {
            case "full":
            case "diag":
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
     * Evaluate multi-D Gaussian CDF for diagonal/spherical covariance.
     */
    private double evaluateGaussianCDF_Diagonal(double[] point, double[] mean,
                                                 double[][] covariance, String covType, int d) {
        double cdf = 1.0;
        
        for (int i = 0; i < d; i++) {
            double variance = covType.equals("spherical") ? 
                covariance[0][0] : covariance[0][i];
            double marginalCDF = evaluateGaussianCDF_1D(point[i], mean[i], variance);
            cdf *= marginalCDF;
        }
        
        return cdf;
    }
    
    /**
     * Evaluate multi-D Gaussian CDF using Monte Carlo sampling.
     */
    private double evaluateGaussianCDF_MonteCarlo(double[] point, double[] mean,
                                                   double[][] covariance, int d) {
        int count = 0;
        double[][] L = MatrixUtils.choleskyDecomposition(covariance, d);
        
        for (int s = 0; s < MC_SAMPLES; s++) {
            double[] z = new double[d];
            for (int i = 0; i < d; i++) {
                z[i] = random.nextGaussian();
            }
            
            double[] sample = new double[d];
            for (int i = 0; i < d; i++) {
                sample[i] = mean[i];
                for (int j = 0; j < d; j++) {
                    sample[i] += L[i][j] * z[j];
                }
            }
            
            boolean allLessOrEqual = true;
            for (int i = 0; i < d; i++) {
                if (sample[i] > point[i]) {
                    allLessOrEqual = false;
                    break;
                }
            }
            
            if (allLessOrEqual) count++;
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
    
    /**
     * Compute log(Σ exp(x_i)) using the log-sum-exp trick for numerical stability.
     */
    private double logSumExp(double[] logValues) {
        // Find maximum
        double maxLog = logValues[0];
        for (int i = 1; i < logValues.length; i++) {
            if (logValues[i] > maxLog) {
                maxLog = logValues[i];
            }
        }
        
        // Handle case where all values are -infinity
        if (Double.isInfinite(maxLog) && maxLog < 0) {
            return Double.NEGATIVE_INFINITY;
        }
        
        // Compute sum of exp(x_i - max)
        double sum = 0.0;
        for (double logValue : logValues) {
            if (!Double.isInfinite(logValue)) {
                sum += Math.exp(logValue - maxLog);
            }
        }
        
        return maxLog + Math.log(sum);
    }
}
