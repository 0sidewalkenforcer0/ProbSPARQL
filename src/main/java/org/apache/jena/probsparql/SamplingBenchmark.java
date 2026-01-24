package org.apache.jena.probsparql;

import org.apache.jena.probsparql.datatypes.GMMValue;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Benchmark to test the impact of Monte Carlo sampling count on performance.
 */
public class SamplingBenchmark {
    
    private static final Random random = new Random(42);
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║          Monte Carlo Sampling Impact Benchmark                     ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Test different sample counts
        int[] sampleCounts = {100, 500, 1000, 2000, 5000, 10000, 20000};
        
        // Test different GMM complexity
        int[] kValues = {1, 2, 3, 5};
        
        // Number of JS divergence computations per test
        int numPairs = 100;
        
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("PART 1: SAMPLING COUNT IMPACT (K=2 fixed, " + numPairs + " pairs)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        // Generate GMM pairs (K=2)
        GMMValue[] gmms1 = new GMMValue[numPairs];
        GMMValue[] gmms2 = new GMMValue[numPairs];
        for (int i = 0; i < numPairs; i++) {
            gmms1[i] = generateGMM(2);
            gmms2[i] = generateGMM(2);
        }
        
        double[] sampleResults = new double[sampleCounts.length];
        
        for (int s = 0; s < sampleCounts.length; s++) {
            int samples = sampleCounts[s];
            
            // Warmup
            for (int i = 0; i < 10; i++) {
                computeJSDivergence(gmms1[0], gmms2[0], samples);
            }
            
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < numPairs; i++) {
                computeJSDivergence(gmms1[i], gmms2[i], samples);
            }
            long elapsed = System.nanoTime() - start;
            double avgMs = elapsed / 1_000_000.0 / numPairs;
            sampleResults[s] = avgMs;
            
            System.out.printf("  Samples=%5d: %.3f ms per pair%n", samples, avgMs);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("PART 2: GMM COMPLEXITY IMPACT (1000 samples fixed, " + numPairs + " pairs)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        double[] kResults = new double[kValues.length];
        
        for (int k = 0; k < kValues.length; k++) {
            int K = kValues[k];
            
            // Generate GMM pairs with specific K
            GMMValue[] gmmK1 = new GMMValue[numPairs];
            GMMValue[] gmmK2 = new GMMValue[numPairs];
            for (int i = 0; i < numPairs; i++) {
                gmmK1[i] = generateGMM(K);
                gmmK2[i] = generateGMM(K);
            }
            
            // Warmup
            for (int i = 0; i < 10; i++) {
                computeJSDivergence(gmmK1[0], gmmK2[0], 1000);
            }
            
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < numPairs; i++) {
                computeJSDivergence(gmmK1[i], gmmK2[i], 1000);
            }
            long elapsed = System.nanoTime() - start;
            double avgMs = elapsed / 1_000_000.0 / numPairs;
            kResults[k] = avgMs;
            
            System.out.printf("  K=%d components: %.3f ms per pair%n", K, avgMs);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS TABLE:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        System.out.println("Sampling Count Impact (K=2):");
        System.out.println("| Samples | Time (ms) | Ratio vs 1000 |");
        System.out.println("|---------|-----------|---------------|");
        double baseline1000 = sampleResults[2]; // 1000 samples
        for (int s = 0; s < sampleCounts.length; s++) {
            System.out.printf("| %6d  |   %.3f   |     %.2fx     |%n", 
                sampleCounts[s], sampleResults[s], sampleResults[s] / baseline1000);
        }
        
        System.out.println();
        System.out.println("GMM Complexity Impact (1000 samples):");
        System.out.println("| K | Time (ms) | Ratio vs K=1 |");
        System.out.println("|---|-----------|--------------|");
        double baselineK1 = kResults[0]; // K=1
        for (int k = 0; k < kValues.length; k++) {
            System.out.printf("| %d |   %.3f   |    %.2fx     |%n", 
                kValues[k], kResults[k], kResults[k] / baselineK1);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.printf("Samples 100→20000 (200x increase): %.3f ms → %.3f ms (%.1fx)%n",
            sampleResults[0], sampleResults[sampleCounts.length-1],
            sampleResults[sampleCounts.length-1] / sampleResults[0]);
        System.out.printf("K=1→K=5 components: %.3f ms → %.3f ms (%.1fx)%n",
            kResults[0], kResults[kValues.length-1],
            kResults[kValues.length-1] / kResults[0]);
    }
    
    /**
     * Compute JS divergence using Monte Carlo approximation.
     */
    private static double computeJSDivergence(GMMValue p, GMMValue q, int numSamples) {
        // Create mixture distribution M = 0.5*P + 0.5*Q
        GMMValue m = createMixture(p, q);
        
        // Compute D_KL(P||M) and D_KL(Q||M)
        double klPM = computeKLDivergence(p, m, numSamples / 2);
        double klQM = computeKLDivergence(q, m, numSamples / 2);
        
        return 0.5 * klPM + 0.5 * klQM;
    }
    
    /**
     * Create mixture of two GMMs with equal weights.
     */
    private static GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getK();
        int kQ = q.getK();
        int newK = kP + kQ;
        int d = p.getD();
        
        double[] newWeights = new double[newK];
        double[][] newMeans = new double[newK][d];
        double[][][] newCovariances = new double[newK][d][d];
        
        // Add components from P with 0.5 weight
        double[] pWeights = p.getWeights();
        double[][] pMeans = p.getMeans();
        double[][][] pCovs = p.getCovariances();
        for (int i = 0; i < kP; i++) {
            newWeights[i] = 0.5 * pWeights[i];
            System.arraycopy(pMeans[i], 0, newMeans[i], 0, d);
            for (int j = 0; j < d; j++) {
                System.arraycopy(pCovs[i][j], 0, newCovariances[i][j], 0, d);
            }
        }
        
        // Add components from Q with 0.5 weight
        double[] qWeights = q.getWeights();
        double[][] qMeans = q.getMeans();
        double[][][] qCovs = q.getCovariances();
        for (int i = 0; i < kQ; i++) {
            newWeights[kP + i] = 0.5 * qWeights[i];
            System.arraycopy(qMeans[i], 0, newMeans[kP + i], 0, d);
            for (int j = 0; j < d; j++) {
                System.arraycopy(qCovs[i][j], 0, newCovariances[kP + i][j], 0, d);
            }
        }
        
        return new GMMValue(newK, d, "full", newWeights, newMeans, newCovariances);
    }
    
    /**
     * Compute KL divergence D_KL(p||q) using Monte Carlo.
     */
    private static double computeKLDivergence(GMMValue p, GMMValue q, int numSamples) {
        double sum = 0.0;
        for (int i = 0; i < numSamples; i++) {
            double[] sample = sampleFromGMM(p);
            double logP = computeLogPDF(p, sample);
            double logQ = computeLogPDF(q, sample);
            sum += logP - logQ;
        }
        return sum / numSamples;
    }
    
    /**
     * Sample from GMM.
     */
    private static double[] sampleFromGMM(GMMValue gmm) {
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        int d = gmm.getD();
        
        int component = sampleCategorical(weights);
        return sampleGaussian(means[component], covariances[component], covType, d);
    }
    
    /**
     * Sample from categorical distribution.
     */
    private static int sampleCategorical(double[] weights) {
        double u = random.nextDouble();
        double cumsum = 0.0;
        for (int i = 0; i < weights.length; i++) {
            cumsum += weights[i];
            if (u <= cumsum) return i;
        }
        return weights.length - 1;
    }
    
    /**
     * Sample from multivariate Gaussian.
     */
    private static double[] sampleGaussian(double[] mean, double[][] covariance, String covType, int d) {
        double[] z = new double[d];
        for (int i = 0; i < d; i++) {
            z[i] = random.nextGaussian();
        }
        
        double[] result = new double[d];
        if ("spherical".equals(covType)) {
            double std = Math.sqrt(covariance[0][0]);
            for (int i = 0; i < d; i++) {
                result[i] = mean[i] + std * z[i];
            }
        } else if ("diag".equals(covType)) {
            for (int i = 0; i < d; i++) {
                result[i] = mean[i] + Math.sqrt(covariance[i][i]) * z[i];
            }
        } else { // full
            double[][] L = choleskyDecomposition(covariance);
            for (int i = 0; i < d; i++) {
                result[i] = mean[i];
                for (int j = 0; j <= i; j++) {
                    result[i] += L[i][j] * z[j];
                }
            }
        }
        return result;
    }
    
    /**
     * Cholesky decomposition.
     */
    private static double[][] choleskyDecomposition(double[][] A) {
        int n = A.length;
        double[][] L = new double[n][n];
        
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }
                if (i == j) {
                    L[i][j] = Math.sqrt(Math.max(A[i][i] - sum, 1e-10));
                } else {
                    L[i][j] = (A[i][j] - sum) / L[j][j];
                }
            }
        }
        return L;
    }
    
    /**
     * Compute log PDF of GMM at point x.
     */
    private static double computeLogPDF(GMMValue gmm, double[] x) {
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        int K = gmm.getK();
        
        double maxLogComp = Double.NEGATIVE_INFINITY;
        double[] logComps = new double[K];
        
        for (int k = 0; k < K; k++) {
            logComps[k] = Math.log(weights[k]) + logGaussianPDF(x, means[k], covariances[k]);
            maxLogComp = Math.max(maxLogComp, logComps[k]);
        }
        
        // Log-sum-exp for numerical stability
        double sum = 0.0;
        for (int k = 0; k < K; k++) {
            sum += Math.exp(logComps[k] - maxLogComp);
        }
        
        return maxLogComp + Math.log(sum);
    }
    
    /**
     * Compute log PDF of Gaussian.
     */
    private static double logGaussianPDF(double[] x, double[] mean, double[][] cov) {
        int d = x.length;
        double[] diff = new double[d];
        for (int i = 0; i < d; i++) {
            diff[i] = x[i] - mean[i];
        }
        
        // Compute determinant and inverse for 1D case (simplified)
        if (d == 1) {
            double var = cov[0][0];
            double mahal = diff[0] * diff[0] / var;
            return -0.5 * (Math.log(2 * Math.PI) + Math.log(var) + mahal);
        }
        
        // For higher dimensions, use simple diagonal approximation
        double logDet = 0.0;
        double mahal = 0.0;
        for (int i = 0; i < d; i++) {
            logDet += Math.log(cov[i][i]);
            mahal += diff[i] * diff[i] / cov[i][i];
        }
        
        return -0.5 * (d * Math.log(2 * Math.PI) + logDet + mahal);
    }
    
    /**
     * Generate a random GMM with K components and d=1 dimension.
     */
    private static GMMValue generateGMM(int k) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        
        double[] weights = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            weights[i] = rand.nextDouble(0.1, 1.0);
            sum += weights[i];
        }
        for (int i = 0; i < k; i++) {
            weights[i] /= sum;
        }
        
        double[][] means = new double[k][1];
        for (int i = 0; i < k; i++) {
            means[i][0] = rand.nextDouble(5.0, 15.0);
        }
        
        double[][][] covariances = new double[k][1][1];
        for (int i = 0; i < k; i++) {
            covariances[i][0][0] = rand.nextDouble(0.1, 2.0);
        }
        
        return new GMMValue(k, 1, "full", weights, means, covariances);
    }
}
