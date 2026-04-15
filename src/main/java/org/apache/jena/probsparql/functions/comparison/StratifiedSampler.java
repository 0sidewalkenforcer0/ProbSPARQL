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
        int K = p.getNComponents();
        double[] weights = p.getWeights();
        
        // Allocate samples to each component based on weight
        int[] samplesPerComponent = allocateSamples(weights, numSamples);
        double estimate = 0.0;
        
        for (int k = 0; k < K; k++) {
            int compSamples = samplesPerComponent[k];
            if (compSamples == 0) continue;

            double compSum = 0.0;
            for (int i = 0; i < compSamples; i++) {
                double[] sample = p.sampleComponent(k, random);
                double logP = p.logPdf(sample);
                double logQ = q.logPdf(sample);
                compSum += (logP - logQ);
            }

            estimate += weights[k] * (compSum / compSamples);
        }
        
        return estimate;
    }
    
    /**
     * Allocate samples to each component proportional to its weight.
     */
    private int[] allocateSamples(double[] weights, int totalSamples) {
        int K = weights.length;
        int[] samples = new int[K];
        double[] remainders = new double[K];
        int allocated = 0;
        
        // First pass: floor the exact allocation.
        for (int k = 0; k < K; k++) {
            double exact = weights[k] * totalSamples;
            samples[k] = (int) Math.floor(exact);
            remainders[k] = exact - samples[k];
            allocated += samples[k];
        }

        // Distribute remaining samples to the largest fractional remainders.
        int remaining = totalSamples - allocated;
        while (remaining > 0) {
            int best = 0;
            for (int k = 1; k < K; k++) {
                if (remainders[k] > remainders[best]) best = k;
            }
            samples[best]++;
            remainders[best] = -1.0;
            remaining--;
        }
        
        return samples;
    }
    
    /**
     * Create mixture distribution M = 0.5*P + 0.5*Q.
     */
    private GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getNComponents();
        int kQ = q.getNComponents();
        int kM = kP + kQ;
        int d = p.getDimensions();
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
            double[][] copy = new double[1][cov[0].length];
            System.arraycopy(cov[0], 0, copy[0], 0, cov[0].length);
            return copy;
        } else {
            return new double[][] {{cov[0][0]}};
        }
    }
    
}
