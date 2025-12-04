package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the logarithm of Probability Density Function (Log-PDF) 
 * of a Gaussian Mixture Model at a given point.
 * 
 * <p>Log-PDF is numerically more stable than PDF for very small probabilities
 * and is commonly used in machine learning and statistics.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?logDensity WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:logpdf(?gmm, 6.0) AS ?logDensity)
 * }
 * </pre>
 * 
 * <p>For a GMM with K components:</p>
 * <pre>
 * log(PDF(x)) = log(Σ(k=1 to K) w_k * N(x | μ_k, Σ_k))
 * </pre>
 * 
 * Uses log-sum-exp trick for numerical stability.
 * 
 * @author ProbSPARQL Team
 */
public class LogPDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#logpdf";
    
    /**
     * Evaluate Log-PDF at a point.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return Log-PDF value as double
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue pointNode) {
        // Extract GMM from first argument
        GMMValue gmm = extractGMM(gmmNode);
        
        // Extract evaluation point
        double[] point = extractPoint(pointNode, gmm.getD());
        
        // Compute Log-PDF
        double logDensity = computeLogPDF(gmm, point);
        
        return NodeValue.makeDouble(logDensity);
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
     * Compute Log-PDF value for a GMM at given point.
     * 
     * Uses log-sum-exp trick for numerical stability:
     * log(Σ exp(x_i)) = max(x_i) + log(Σ exp(x_i - max(x_i)))
     */
    private double computeLogPDF(GMMValue gmm, double[] point) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Compute log(w_k) + log(N(x | μ_k, Σ_k)) for each component
        double[] logComponents = new double[K];
        
        for (int k = 0; k < K; k++) {
            double logWeight = Math.log(weights[k]);
            double logGaussian = evaluateLogGaussian(
                point, 
                means[k], 
                covariances[k],
                covType,
                d
            );
            logComponents[k] = logWeight + logGaussian;
        }
        
        // Use log-sum-exp trick
        return logSumExp(logComponents);
    }
    
    /**
     * Evaluate log of multivariate Gaussian density.
     * 
     * log(N(x | μ, Σ)) = -d/2 * log(2π) - 1/2 * log|Σ| - 1/2 * (x-μ)^T Σ^(-1) (x-μ)
     */
    private double evaluateLogGaussian(double[] x, double[] mean, 
                                       double[][] covariance, 
                                       String covType, int d) {
        double[] diff = MatrixUtils.subtract(x, mean, d);
        
        double logDet;
        double mahalanobis;
        
        if (d == 1) {
            // Optimized 1D case
            double variance = getVariance(covariance, covType);
            logDet = Math.log(variance);
            mahalanobis = (diff[0] * diff[0]) / variance;
        } else {
            // Multi-dimensional case
            double[][] fullCov = toFullCovariance(covariance, covType, d);
            double det = MatrixUtils.determinant(fullCov, d);
            logDet = Math.log(det);
            
            double[][] covInv = MatrixUtils.invertMatrix(fullCov, d);
            mahalanobis = MatrixUtils.quadraticForm(diff, covInv, d);
        }
        
        // Compute log normalization constant
        double logNormalization = -0.5 * d * Math.log(2 * Math.PI) - 0.5 * logDet;
        
        // Compute log of exponential term
        double logExponent = -0.5 * mahalanobis;
        
        return logNormalization + logExponent;
    }
    
    /**
     * Get variance from covariance matrix (1D case).
     */
    private double getVariance(double[][] covariance, String covType) {
        return covariance[0][0];
    }
    
    /**
     * Convert covariance to full matrix form.
     */
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        switch (type) {
            case "full":
                return cov;
                
            case "diag":
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? cov[i][0] : 0.0;
                    }
                }
                return full;
                
            case "spherical":
                double variance = cov[0][0];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? variance : 0.0;
                    }
                }
                return full;
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + type);
        }
    }
    
    /**
     * Compute log(Σ exp(x_i)) using the log-sum-exp trick for numerical stability.
     * 
     * log(Σ exp(x_i)) = max(x_i) + log(Σ exp(x_i - max(x_i)))
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
            sum += Math.exp(logValue - maxLog);
        }
        
        return maxLog + Math.log(sum);
    }
}
