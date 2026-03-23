package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;

import java.util.Random;

/**
 * V2: Stratified Sampler
 * 
 * Stratified sampling improves accuracy by sampling proportionally from each 
 * Gaussian component based on its weight. This reduces tail error compared 
 * to pure Monte Carlo.
 * 
 * @author ProbSPARQL Team
 */
public class StratifiedSampler {
    
    private final Random random;
    
    public StratifiedSampler(long seed) {
        this.random = new Random(seed);
    }
    
    /**
     * Compute JSD using stratified sampling.
     */
    public double computeJSD(GMMValue p, GMMValue q, int numSamples) {
        GMMValue m = createMixture(p, q);
        
        // Stratified sampling: allocate samples proportionally
        double klPM = computeKLStratified(p, m, numSamples / 2);
        double klQM = computeKLStratified(q, m, numSamples / 2);
        
        return 0.5 * klPM + 0.5 * klQM;
    }
    
    /**
     * Compute KL divergence using stratified sampling.
     * Samples are drawn proportionally from each component.
     */
    private double computeKLStratified(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        int K = p.getK();
        
        // Allocate samples to each component based on weight
        int[] samplesPerComponent = allocateSamples(p.getWeights(), numSamples);
        
        for (int k = 0; k < K; k++) {
            int compSamples = samplesPerComponent[k];
            if (compSamples == 0) continue;
            
            double[] mean = p.getMeans()[k];
            double[][] cov = p.getCovariances()[k];
            String covType = p.getCovarianceType();
            int d = p.getD();
            
            for (int i = 0; i < compSamples; i++) {
                double[] sample = sampleGaussian(mean, cov, covType, d);
                double logP = computeLogPDF(p, sample);
                double logQ = computeLogPDF(q, sample);
                sum += (logP - logQ);
            }
        }
        
        return sum / numSamples;
    }
    
    /**
     * Allocate samples to each component proportional to its weight.
     */
    private int[] allocateSamples(double[] weights, int totalSamples) {
        int K = weights.length;
        int[] samples = new int[K];
        
        // First pass: allocate proportionally
        for (int k = 0; k < K; k++) {
            samples[k] = (int) Math.round(weights[k] * totalSamples);
        }
        
        // Adjust to ensure total equals totalSamples
        int allocated = 0;
        for (int s : samples) allocated += s;
        int diff = totalSamples - allocated;
        
        // Distribute remaining samples randomly
        if (diff != 0) {
            int idx = random.nextInt(K);
            samples[idx] += diff;
        }
        
        return samples;
    }
    
    /**
     * Create mixture distribution M = 0.5*P + 0.5*Q.
     */
    private GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getK();
        int kQ = q.getK();
        int kM = kP + kQ;
        int d = p.getD();
        String covType = p.getCovarianceType();
        
        double[] weightsM = new double[kM];
        double[][] meansM = new double[kM][d];
        double[][][] covariancesM = new double[kM][][];
        
        for (int k = 0; k < kP; k++) {
            weightsM[k] = 0.5 * p.getWeights()[k];
            meansM[k] = p.getMeans()[k].clone();
            covariancesM[k] = cloneCovariance(p.getCovariances()[k], covType);
        }
        
        for (int k = 0; k < kQ; k++) {
            weightsM[kP + k] = 0.5 * q.getWeights()[k];
            meansM[kP + k] = q.getMeans()[k].clone();
            covariancesM[kP + k] = cloneCovariance(q.getCovariances()[k], covType);
        }
        
        return new GMMValue(kM, d, covType, weightsM, meansM, covariancesM);
    }
    
    private double[][] cloneCovariance(double[][] cov, String covType) {
        if ("full".equals(covType)) {
            int d = cov.length;
            double[][] copy = new double[d][d];
            for (int i = 0; i < d; i++) {
                System.arraycopy(cov[i], 0, copy[i], 0, d);
            }
            return copy;
        } else if ("diag".equals(covType)) {
            double[][] copy = new double[cov.length][1];
            for (int i = 0; i < cov.length; i++) {
                copy[i][0] = cov[i][0];
            }
            return copy;
        } else {
            return new double[][] {{cov[0][0]}};
        }
    }
    
    private double[] sampleGaussian(double[] mean, double[][] covariance, 
                                   String covType, int d) {
        double[] sample = new double[d];
        
        if (d == 1) {
            double stddev = Math.sqrt(covariance[0][0]);
            sample[0] = mean[0] + stddev * random.nextGaussian();
        } else {
            double[][] fullCov = toFullCovariance(covariance, covType, d);
            double[][] L = MatrixUtils.choleskyDecomposition(fullCov, d);
            
            double[] z = new double[d];
            for (int i = 0; i < d; i++) {
                z[i] = random.nextGaussian();
            }
            
            double[] Lz = MatrixUtils.matrixVectorMultiply(L, z, d);
            for (int i = 0; i < d; i++) {
                sample[i] = mean[i] + Lz[i];
            }
        }
        
        return sample;
    }
    
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
            double logGaussian = evaluateLogGaussian(point, means[k], covariances[k], covType, d);
            logComponents[k] = logWeight + logGaussian;
        }
        
        return logSumExp(logComponents);
    }
    
    private double evaluateLogGaussian(double[] x, double[] mean, 
                                       double[][] covariance, String covType, int d) {
        double[] diff = MatrixUtils.subtract(x, mean, d);
        
        double logDet;
        double mahalanobis;
        
        if (d == 1) {
            double variance = covariance[0][0];
            logDet = Math.log(variance);
            mahalanobis = (diff[0] * diff[0]) / variance;
        } else {
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
    
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        if ("full".equals(type)) {
            return cov;
        } else if ("diag".equals(type)) {
            for (int i = 0; i < d; i++) {
                for (int j = 0; j < d; j++) {
                    full[i][j] = (i == j) ? cov[i][0] : 0.0;
                }
            }
            return full;
        } else {
            double variance = cov[0][0];
            for (int i = 0; i < d; i++) {
                for (int j = 0; j < d; j++) {
                    full[i][j] = (i == j) ? variance : 0.0;
                }
            }
            return full;
        }
    }
    
    private double logSumExp(double[] logValues) {
        double maxLog = logValues[0];
        for (int i = 1; i < logValues.length; i++) {
            if (logValues[i] > maxLog) maxLog = logValues[i];
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
