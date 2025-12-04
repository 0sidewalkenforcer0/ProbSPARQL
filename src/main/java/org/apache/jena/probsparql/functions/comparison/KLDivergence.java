package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the Kullback-Leibler (KL) divergence between two GMMs.
 * 
 * <p>KL divergence measures how one probability distribution diverges from another.
 * It is asymmetric: D_KL(P||Q) ≠ D_KL(Q||P)</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?divergence WHERE {
 *   ?var1 uq:hasDistribution ?gmm1 .
 *   ?var2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:kldivergence(?gmm1, ?gmm2) AS ?divergence)
 * }
 * </pre>
 * 
 * <p>For GMMs, we use Monte Carlo approximation:</p>
 * <pre>
 * D_KL(P||Q) ≈ (1/N) Σ(i=1 to N) [log(p(x_i)) - log(q(x_i))]
 * </pre>
 * where x_i ~ P (samples drawn from first GMM).
 * 
 * @author ProbSPARQL Team
 */
public class KLDivergence extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#kldivergence";
    
    private static final int DEFAULT_SAMPLES = 10000;
    private static final java.util.Random random = new java.util.Random(42); // Fixed seed for reproducibility
    
    /**
     * Compute KL divergence D_KL(gmm1 || gmm2).
     * 
     * @param gmm1Node First GMM (P in D_KL(P||Q))
     * @param gmm2Node Second GMM (Q in D_KL(P||Q))
     * @return KL divergence value (non-negative)
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        
        // Validate compatibility
        if (gmm1.getD() != gmm2.getD()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality. Got d1=" + gmm1.getD() + 
                ", d2=" + gmm2.getD());
        }
        
        double kl = computeKLDivergence(gmm1, gmm2, DEFAULT_SAMPLES);
        
        return NodeValue.makeDouble(kl);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Compute KL divergence using Monte Carlo approximation.
     * 
     * D_KL(P||Q) ≈ (1/N) Σ [log(p(x_i)) - log(q(x_i))] where x_i ~ P
     */
    private double computeKLDivergence(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        
        for (int i = 0; i < numSamples; i++) {
            // Sample from P
            double[] sample = sampleFromGMM(p);
            
            // Compute log(p(x))
            double logP = computeLogPDF(p, sample);
            
            // Compute log(q(x))
            double logQ = computeLogPDF(q, sample);
            
            // Accumulate difference
            sum += (logP - logQ);
        }
        
        return sum / numSamples;
    }
    
    /**
     * Sample a point from a GMM.
     * 
     * 1. Select component k with probability w_k
     * 2. Sample from N(μ_k, Σ_k)
     */
    private double[] sampleFromGMM(GMMValue gmm) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Select component
        int component = sampleCategorical(weights);
        
        // Sample from selected Gaussian
        return sampleGaussian(means[component], covariances[component], covType, d);
    }
    
    /**
     * Sample from categorical distribution.
     */
    private int sampleCategorical(double[] weights) {
        double u = random.nextDouble();
        double cumulative = 0.0;
        
        for (int k = 0; k < weights.length; k++) {
            cumulative += weights[k];
            if (u <= cumulative) {
                return k;
            }
        }
        
        return weights.length - 1;
    }
    
    /**
     * Sample from multivariate Gaussian using Cholesky decomposition.
     */
    private double[] sampleGaussian(double[] mean, double[][] covariance, 
                                    String covType, int d) {
        double[] sample = new double[d];
        
        if (d == 1) {
            // Optimized 1D case
            double stddev = Math.sqrt(getVariance(covariance, covType));
            sample[0] = mean[0] + stddev * random.nextGaussian();
        } else {
            // Multi-dimensional: sample = mean + L * z
            // where z ~ N(0, I) and L is Cholesky decomposition of covariance
            double[][] fullCov = toFullCovariance(covariance, covType, d);
            double[][] L = MatrixUtils.choleskyDecomposition(fullCov, d);
            
            // Generate standard normal samples
            double[] z = new double[d];
            for (int i = 0; i < d; i++) {
                z[i] = random.nextGaussian();
            }
            
            // Compute L * z + mean
            double[] Lz = MatrixUtils.matrixVectorMultiply(L, z, d);
            for (int i = 0; i < d; i++) {
                sample[i] = mean[i] + Lz[i];
            }
        }
        
        return sample;
    }
    
    /**
     * Get variance from covariance matrix.
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
     * Compute log PDF of GMM at a point.
     */
    private double computeLogPDF(GMMValue gmm, double[] point) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        double[] logComponents = new double[K];
        
        for (int k = 0; k < K; k++) {
            double logWeight = Math.log(weights[k]);
            double logGaussian = evaluateLogGaussian(
                point, means[k], covariances[k], covType, d
            );
            logComponents[k] = logWeight + logGaussian;
        }
        
        return logSumExp(logComponents);
    }
    
    /**
     * Evaluate log of Gaussian density.
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
        
        double logNormalization = -0.5 * d * Math.log(2 * Math.PI) - 0.5 * logDet;
        double logExponent = -0.5 * mahalanobis;
        
        return logNormalization + logExponent;
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
     * Log-sum-exp trick for numerical stability.
     */
    private double logSumExp(double[] logValues) {
        double maxLog = logValues[0];
        for (int i = 1; i < logValues.length; i++) {
            if (logValues[i] > maxLog) {
                maxLog = logValues[i];
            }
        }
        
        if (Double.isInfinite(maxLog) && maxLog < 0) {
            return Double.NEGATIVE_INFINITY;
        }
        
        double sum = 0.0;
        for (double logValue : logValues) {
            sum += Math.exp(logValue - maxLog);
        }
        
        return maxLog + Math.log(sum);
    }
}
