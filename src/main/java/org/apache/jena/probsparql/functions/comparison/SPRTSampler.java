package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.utils.MatrixUtils;

import java.util.Random;

/**
 * V3: Sequential hypothesis-test sampler with early stopping.
 * 
 * <p>The public mode name remains {@code V3_SPRT} for compatibility, but the
 * implementation is closer to a sequential confidence-bound test than a
 * textbook Wald likelihood-ratio SPRT.  We repeatedly estimate
 * {@code JSD(P, Q)} with Monte Carlo, normalize the distance to the threshold
 * by the estimated standard error, and stop once one of two one-sided decisions
 * becomes conclusive:</p>
 * 
 * <ul>
 *   <li>reject similarity when the lower confidence bound is above
 *       {@code epsilon}</li>
 *   <li>accept similarity when the upper confidence bound is below
 *       {@code epsilon}</li>
 * </ul>
 * 
 * <p>This keeps the existing early-stop behavior while making the statistical
 * intent explicit in the code.</p>
 * 
 * @author ProbSPARQL Team
 */
public class SPRTSampler {
    private static final int CHECK_INTERVAL_PAIRED_SAMPLES = 50;

    private enum SequentialDecision {
        CONTINUE,
        ACCEPT_SIMILAR,
        REJECT_DISSIMILAR
    }

    /**
     * Running moments for one Monte Carlo quantity such as log p(x) - log m(x).
     */
    private static final class RunningMoments {
        private double sum = 0.0;
        private double sumSquares = 0.0;

        void add(double value) {
            sum += value;
            sumSquares += value * value;
        }

        double mean(int sampleCount) {
            return sum / sampleCount;
        }

        double variance(int sampleCount) {
            double mean = mean(sampleCount);
            return Math.max(0.0, sumSquares / sampleCount - mean * mean);
        }
    }

    /**
     * Snapshot of the current sequential test state after {@code n} paired samples.
     */
    private static final class TestSnapshot {
        private final int pairedSamples;
        private final double jsdEstimate;
        private final double standardError;

        TestSnapshot(int pairedSamples, double jsdEstimate, double standardError) {
            this.pairedSamples = pairedSamples;
            this.jsdEstimate = jsdEstimate;
            this.standardError = standardError;
        }
    }
    
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
     * Compute JSD using sequential MC with hypothesis-test-style early stopping.
     *
     * <p>Each round draws one sample from {@code p} and one from {@code q},
     * builds running estimates of {@code KL(p||m)} and {@code KL(q||m)}, and
     * every {@value #CHECK_INTERVAL_PAIRED_SAMPLES} paired samples checks whether
     * the current JSD confidence bounds lie entirely above or below
     * {@code epsilon}.</p>
     *
     * Returns pair: {jsdValue, actualSamplesUsed}
     */
    public double[] computeJSDWithStats(GMMValue p, GMMValue q, int maxSamples) {
        GMMValue m = createMixture(p, q);
        double zReject = inverseStandardNormalCDF(1.0 - alpha);
        double zAccept = inverseStandardNormalCDF(1.0 - beta);

        int maxPairedSamples = maxSamples / 2;
        int pairedSamples = 0;
        RunningMoments klPmMoments = new RunningMoments();
        RunningMoments klQmMoments = new RunningMoments();

        while (pairedSamples < maxPairedSamples) {
            // For X ~ P, E[log p(X) - log m(X)] = KL(P || M).
            double[] xp = p.sampleOne(random);
            double vp = p.logPdf(xp) - m.logPdf(xp);
            klPmMoments.add(vp);

            // For Y ~ Q, E[log q(Y) - log m(Y)] = KL(Q || M).
            double[] xq = q.sampleOne(random);
            double vq = q.logPdf(xq) - m.logPdf(xq);
            klQmMoments.add(vq);

            pairedSamples++;

            if (!shouldCheckDecision(pairedSamples)) {
                continue;
            }

            TestSnapshot snapshot = buildSnapshot(pairedSamples, klPmMoments, klQmMoments);
            SequentialDecision decision = evaluateDecision(snapshot, zReject, zAccept);
            if (decision == SequentialDecision.REJECT_DISSIMILAR) {
                earlyRejected++;
                totalSamples += pairedSamples * 2;
                return new double[] {snapshot.jsdEstimate, pairedSamples * 2};
            }
            if (decision == SequentialDecision.ACCEPT_SIMILAR) {
                earlyAccepted++;
                totalSamples += pairedSamples * 2;
                return new double[] {snapshot.jsdEstimate, pairedSamples * 2};
            }
        }

        // Ran to budget without convergence — return best estimate
        fullSamples++;
        totalSamples += maxSamples;
        TestSnapshot finalSnapshot = buildSnapshot(maxPairedSamples, klPmMoments, klQmMoments);
        return new double[] {finalSnapshot.jsdEstimate, maxSamples};
    }
    
    /**
     * Compute JSD using SPRT (simpler version without stats).
     */
    public double computeJSD(GMMValue p, GMMValue q, int maxSamples) {
        return computeJSDWithStats(p, q, maxSamples)[0];
    }

    private boolean shouldCheckDecision(int pairedSamples) {
        return pairedSamples >= CHECK_INTERVAL_PAIRED_SAMPLES
            && pairedSamples % CHECK_INTERVAL_PAIRED_SAMPLES == 0;
    }

    private TestSnapshot buildSnapshot(int pairedSamples,
                                       RunningMoments klPmMoments,
                                       RunningMoments klQmMoments) {
        double klPmEstimate = klPmMoments.mean(pairedSamples);
        double klQmEstimate = klQmMoments.mean(pairedSamples);
        double jsdEstimate = Math.max(0.0, 0.5 * (klPmEstimate + klQmEstimate));

        double varP = klPmMoments.variance(pairedSamples);
        double varQ = klQmMoments.variance(pairedSamples);
        double standardError = 0.5 * Math.sqrt((varP + varQ) / pairedSamples);

        return new TestSnapshot(pairedSamples, jsdEstimate, standardError);
    }

    /**
     * Decide whether the current estimate already supports one of the two
     * one-sided conclusions relative to the threshold epsilon.
     *
     * <p>Written in confidence-bound form:
     * {@code jsdHat - z * se > epsilon} is equivalent to a large positive
     * normalized test statistic {@code (jsdHat - epsilon) / se}, and
     * {@code jsdHat + z * se < epsilon} is the symmetric lower-tail decision.</p>
     */
    private SequentialDecision evaluateDecision(TestSnapshot snapshot,
                                                double zReject,
                                                double zAccept) {
        if (snapshot.standardError <= 0.0) {
            return SequentialDecision.CONTINUE;
        }

        if (snapshot.jsdEstimate - zReject * snapshot.standardError > epsilon) {
            return SequentialDecision.REJECT_DISSIMILAR;
        }
        if (snapshot.jsdEstimate + zAccept * snapshot.standardError < epsilon) {
            return SequentialDecision.ACCEPT_SIMILAR;
        }
        return SequentialDecision.CONTINUE;
    }
    
    /**
     * Standard Monte Carlo KL computation.
     */
    private double computeKLMonteCarlo(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        
        for (int i = 0; i < numSamples; i++) {
            double[] sample = p.sampleOne(random);
            double logP = p.logPdf(sample);
            double logQ = q.logPdf(sample);
            sum += (logP - logQ);
        }
        
        return sum / numSamples;
    }
    
    private double[] sampleFromGMM(GMMValue gmm) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        
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
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
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
                    full[i][j] = (i == j) ? cov[0][i] : 0.0;
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

    private static double inverseStandardNormalCDF(double p) {
        if (!(p > 0.0 && p < 1.0)) {
            throw new IllegalArgumentException("p must be in (0,1), got " + p);
        }
        // Peter J. Acklam's rational approximation.
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02,
            -2.759285104469687e+02, 1.383577518672690e+02,
            -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02,
            -1.556989798598866e+02, 6.680131188771972e+01,
            -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01,
            -2.400758277161838e+00, -2.549732539343734e+00,
            4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01,
            2.445134137142996e+00, 3.754408661907416e+00};
        double plow = 0.02425;
        double phigh = 1.0 - plow;
        if (p < plow) {
            double q = Math.sqrt(-2.0 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                   ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        if (p > phigh) {
            double q = Math.sqrt(-2.0 * Math.log(1.0 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) /
                    ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1.0);
        }
        double q = p - 0.5;
        double r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q /
               (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1.0);
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
