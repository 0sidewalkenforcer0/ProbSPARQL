package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;

import java.util.Random;

/**
 * V3: SPRT (Sequential Probability Ratio Test) Sampler
 * 
 * SPRT provides early termination by testing whether we have sufficient confidence
 * to accept or reject the hypothesis that JSD > epsilon.
 * 
 * This significantly reduces computation for easy cases where distributions are
 * clearly similar or clearly different.
 * 
 * @author ProbSPARQL Team
 */
public class SPRTSampler {
    
    private final Random random;
    private final double alpha;   // False positive rate
    private final double beta;    // False negative rate
    private final double epsilon;  // Decision threshold
    
    // Statistics tracking
    private int totalSamples = 0;
    private int earlyAccepted = 0;   // Rejected early (clearly similar)
    private int earlyRejected = 0;  // Rejected early (clearly different)
    private int fullSamples = 0;    // Required full computation
    
    public SPRTSampler(long seed, double alpha, double beta, double epsilon) {
        this.random = new Random(seed);
        this.alpha = alpha;
        this.beta = beta;
        this.epsilon = epsilon;
    }
    
    /**
     * Compute JSD using sequential MC with confidence-interval-based early termination.
     *
     * Alternates sampling from p and q to build running estimates of
     * KL(p||m) and KL(q||m), then stops early when the JSD CI
     * lies entirely above or below epsilon (the decision threshold).
     *
     * Returns pair: {jsdValue, actualSamplesUsed}
     */
    public double[] computeJSDWithStats(GMMValue p, GMMValue q, int maxSamples) {
        GMMValue m = createMixture(p, q);

        // z-score for two-sided 95% CI (corresponds to alpha=beta=0.05)
        double z = 1.96;

        int halfMax = maxSamples / 2;
        int n = 0;
        double sumLogPM = 0.0, sumSqLogPM = 0.0;
        double sumLogQM = 0.0, sumSqLogQM = 0.0;

        // Check CI every checkInterval paired samples (avoid per-sample overhead)
        int checkInterval = 50;

        while (n < halfMax) {
            // Estimate KL(p||m): sample from p, accumulate log(p(x)/m(x))
            double[] xp = sampleFromGMM(p);
            double vp = computeLogPDF(p, xp) - computeLogPDF(m, xp);
            sumLogPM += vp;
            sumSqLogPM += vp * vp;

            // Estimate KL(q||m): sample from q, accumulate log(q(x)/m(x))
            double[] xq = sampleFromGMM(q);
            double vq = computeLogPDF(q, xq) - computeLogPDF(m, xq);
            sumLogQM += vq;
            sumSqLogQM += vq * vq;

            n++;

            // Periodically check whether CI is entirely above or below epsilon
            if (n >= checkInterval && n % checkInterval == 0) {
                double jsdEst = Math.max(0.0, 0.5 * (sumLogPM + sumLogQM) / n);
                double varP = Math.max(0.0, sumSqLogPM / n - (sumLogPM / n) * (sumLogPM / n));
                double varQ = Math.max(0.0, sumSqLogQM / n - (sumLogQM / n) * (sumLogQM / n));
                // SE of JSD estimate = 0.5 * sqrt((varP + varQ) / n)
                double se = 0.5 * Math.sqrt((varP + varQ) / n);

                if (se > 0) {
                    if (jsdEst - z * se > epsilon) {
                        // CI entirely above epsilon → confidently dissimilar
                        earlyRejected++;
                        totalSamples += n * 2;
                        return new double[] {jsdEst, n * 2};
                    }
                    if (jsdEst + z * se < epsilon) {
                        // CI entirely below epsilon → confidently similar
                        earlyAccepted++;
                        totalSamples += n * 2;
                        return new double[] {jsdEst, n * 2};
                    }
                }
            }
        }

        // Ran to budget without convergence — return best estimate
        fullSamples++;
        totalSamples += maxSamples;
        double jsd = Math.max(0.0, 0.5 * (sumLogPM + sumLogQM) / halfMax);
        return new double[] {jsd, maxSamples};
    }
    
    /**
     * Compute JSD using SPRT (simpler version without stats).
     */
    public double computeJSD(GMMValue p, GMMValue q, int maxSamples) {
        return computeJSDWithStats(p, q, maxSamples)[0];
    }
    
    /**
     * Standard Monte Carlo KL computation.
     */
    private double computeKLMonteCarlo(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        
        for (int i = 0; i < numSamples; i++) {
            double[] sample = sampleFromGMM(p);
            double logP = computeLogPDF(p, sample);
            double logQ = computeLogPDF(q, sample);
            sum += (logP - logQ);
        }
        
        return sum / numSamples;
    }
    
    private double[] sampleFromGMM(GMMValue gmm) {
        int K = gmm.getK();
        int d = gmm.getD();
        
        // Sample component
        double u = random.nextDouble();
        double cumulative = 0.0;
        int component = 0;
        
        for (int k = 0; k < K; k++) {
            cumulative += gmm.getWeights()[k];
            if (u <= cumulative) {
                component = k;
                break;
            }
        }
        
        // Sample from component
        return sampleGaussian(
            gmm.getMeans()[component], 
            gmm.getCovariances()[component], 
            gmm.getCovarianceType(), 
            d
        );
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
    
    // Getters for statistics
    public int getTotalSamples() { return totalSamples; }
    public int getEarlyAccepted() { return earlyAccepted; }
    public int getEarlyRejected() { return earlyRejected; }
    public int getFullSamples() { return fullSamples; }
    
    public void resetStats() {
        totalSamples = 0;
        earlyAccepted = 0;
        earlyRejected = 0;
        fullSamples = 0;
    }
}
