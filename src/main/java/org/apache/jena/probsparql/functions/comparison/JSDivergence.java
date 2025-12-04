package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the Jensen-Shannon (JS) divergence between two GMMs.
 * 
 * <p>JS divergence is a symmetric and smoothed version of KL divergence.
 * It is always finite and bounded: 0 ≤ JS(P||Q) ≤ log(2)</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?divergence WHERE {
 *   ?var1 uq:hasDistribution ?gmm1 .
 *   ?var2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?divergence)
 * }
 * </pre>
 * 
 * <p>JS divergence is defined as:</p>
 * <pre>
 * JS(P||Q) = 0.5 * D_KL(P||M) + 0.5 * D_KL(Q||M)
 * </pre>
 * where M = 0.5 * (P + Q) is the mixture distribution.
 * 
 * <p>Properties:</p>
 * <ul>
 *   <li>Symmetric: JS(P||Q) = JS(Q||P)</li>
 *   <li>Non-negative: JS(P||Q) ≥ 0</li>
 *   <li>Bounded: JS(P||Q) ≤ log(2) ≈ 0.693</li>
 *   <li>Square root is a metric (Jensen-Shannon distance)</li>
 * </ul>
 * 
 * @author ProbSPARQL Team
 */
public class JSDivergence extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#jsdivergence";
    
    private static final int DEFAULT_SAMPLES = 10000;
    private static final java.util.Random random = new java.util.Random(42);
    
    /**
     * Compute JS divergence JS(gmm1 || gmm2).
     * 
     * @param gmm1Node First GMM
     * @param gmm2Node Second GMM
     * @return JS divergence value in [0, log(2)]
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
        
        double js = computeJSDivergence(gmm1, gmm2, DEFAULT_SAMPLES);
        
        return NodeValue.makeDouble(js);
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
     * Compute JS divergence using Monte Carlo approximation.
     * 
     * JS(P||Q) = 0.5 * D_KL(P||M) + 0.5 * D_KL(Q||M)
     * where M = 0.5 * (P + Q)
     */
    private double computeJSDivergence(GMMValue p, GMMValue q, int numSamples) {
        // Create mixture distribution M = 0.5*P + 0.5*Q
        GMMValue m = createMixture(p, q);
        
        // Compute D_KL(P||M)
        double klPM = computeKLDivergence(p, m, numSamples / 2);
        
        // Compute D_KL(Q||M)
        double klQM = computeKLDivergence(q, m, numSamples / 2);
        
        // JS = 0.5 * D_KL(P||M) + 0.5 * D_KL(Q||M)
        return 0.5 * klPM + 0.5 * klQM;
    }
    
    /**
     * Create mixture distribution M = 0.5*P + 0.5*Q.
     * 
     * The mixture is a GMM with K_P + K_Q components.
     */
    private GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getK();
        int kQ = q.getK();
        int kM = kP + kQ;
        int d = p.getD();
        
        double[] weightsP = p.getWeights();
        double[] weightsQ = q.getWeights();
        double[][] meansP = p.getMeans();
        double[][] meansQ = q.getMeans();
        double[][][] covariancesP = p.getCovariances();
        double[][][] covariancesQ = q.getCovariances();
        
        // Assume same covariance type
        String covType = p.getCovarianceType();
        
        // Construct mixture
        double[] weightsM = new double[kM];
        double[][] meansM = new double[kM][d];
        double[][][] covariancesM = new double[kM][][];
        
        // First K_P components from P with weight 0.5 * w_k
        for (int k = 0; k < kP; k++) {
            weightsM[k] = 0.5 * weightsP[k];
            meansM[k] = meansP[k].clone();
            covariancesM[k] = cloneCovariance(covariancesP[k], covType);
        }
        
        // Next K_Q components from Q with weight 0.5 * w_k
        for (int k = 0; k < kQ; k++) {
            weightsM[kP + k] = 0.5 * weightsQ[k];
            meansM[kP + k] = meansQ[k].clone();
            covariancesM[kP + k] = cloneCovariance(covariancesQ[k], covType);
        }
        
        return new GMMValue(kM, d, covType, weightsM, meansM, covariancesM);
    }
    
    /**
     * Clone covariance matrix based on type.
     */
    private double[][] cloneCovariance(double[][] cov, String covType) {
        switch (covType) {
            case "full":
                // Deep copy the full d×d matrix
                int d = cov.length;
                double[][] fullCopy = new double[d][d];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        fullCopy[i][j] = cov[i][j];
                    }
                }
                return fullCopy;
            case "diag":
                // For diagonal: each row has 1 element (variance)
                double[][] diagCopy = new double[cov.length][1];
                for (int i = 0; i < cov.length; i++) {
                    diagCopy[i][0] = cov[i][0];
                }
                return diagCopy;
            case "spherical":
                // For spherical: single variance value
                return new double[][] {{cov[0][0]}};
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
    
    /**
     * Compute KL divergence D_KL(p||q) using Monte Carlo.
     */
    private double computeKLDivergence(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        
        for (int i = 0; i < numSamples; i++) {
            double[] sample = sampleFromGMM(p);
            double logP = computeLogPDF(p, sample);
            double logQ = computeLogPDF(q, sample);
            sum += (logP - logQ);
        }
        
        return sum / numSamples;
    }
    
    /**
     * Sample from GMM.
     */
    private double[] sampleFromGMM(GMMValue gmm) {
        int K = gmm.getK();
        int d = gmm.getD();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        int component = sampleCategorical(weights);
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
        return covariance[0][0];
    }
    
    /**
     * Compute log PDF.
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
     * Evaluate log Gaussian density.
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
     * Log-sum-exp trick.
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
