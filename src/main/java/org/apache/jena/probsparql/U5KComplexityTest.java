package org.apache.jena.probsparql;

import org.apache.jena.probsparql.datatypes.GMMValue;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test U5 JS Divergence with fixed 5000 entities, 10000 samples, varying K.
 */
public class U5KComplexityTest {
    
    private static final Random random = new Random(42);
    private static final int NUM_ENTITIES = 5000;
    private static final int NUM_SAMPLES = 10000;
    private static final int NUM_PAIRS = 100;
    
    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║  U5 JS Divergence: GMM Complexity Impact (K=1 to K=10)            ║");
        System.out.println("║  Fixed: 5000 entities, 10000 samples                               ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        int[] kValues = {1, 2, 3, 5, 7, 10};
        double[] times = new double[kValues.length];
        
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("Testing per-pair JS Divergence time with different K values...");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        for (int k = 0; k < kValues.length; k++) {
            int K = kValues[k];
            
            // Generate GMM pairs
            GMMValue[] gmms1 = new GMMValue[NUM_PAIRS];
            GMMValue[] gmms2 = new GMMValue[NUM_PAIRS];
            for (int i = 0; i < NUM_PAIRS; i++) {
                gmms1[i] = generateGMM(K);
                gmms2[i] = generateGMM(K);
            }
            
            // Warmup
            for (int i = 0; i < 3; i++) {
                computeJSDivergence(gmms1[0], gmms2[0], NUM_SAMPLES);
            }
            
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < NUM_PAIRS; i++) {
                computeJSDivergence(gmms1[i], gmms2[i], NUM_SAMPLES);
            }
            long elapsed = System.nanoTime() - start;
            times[k] = elapsed / 1_000_000.0 / NUM_PAIRS;
            
            System.out.printf("  K=%2d: %.3f ms per pair%n", K, times[k]);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS TABLE:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("┌────────┬───────────────────┬────────────────┐");
        System.out.println("│ K      │ Time per pair(ms) │ Ratio vs K=1   │");
        System.out.println("├────────┼───────────────────┼────────────────┤");
        for (int k = 0; k < kValues.length; k++) {
            double ratio = times[k] / times[0];
            System.out.printf("│ %6d │ %17.3f │ %14.2fx │%n", kValues[k], times[k], ratio);
        }
        System.out.println("└────────┴───────────────────┴────────────────┘");
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.printf("Configuration: %d entities, %d samples%n", NUM_ENTITIES, NUM_SAMPLES);
        System.out.printf("K=1  → K=%d: %.3f ms → %.3f ms (%.2fx increase)%n", 
            kValues[kValues.length-1], times[0], times[times.length-1], 
            times[times.length-1] / times[0]);
    }
    
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
    
    private static double computeJSDivergence(GMMValue p, GMMValue q, int numSamples) {
        GMMValue m = createMixture(p, q);
        double klPM = computeKLDivergence(p, m, numSamples / 2);
        double klQM = computeKLDivergence(q, m, numSamples / 2);
        return 0.5 * klPM + 0.5 * klQM;
    }
    
    private static GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getK();
        int kQ = q.getK();
        int newK = kP + kQ;
        int d = p.getD();
        
        double[] newWeights = new double[newK];
        double[][] newMeans = new double[newK][d];
        double[][][] newCovariances = new double[newK][d][d];
        
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
    
    private static double[] sampleFromGMM(GMMValue gmm) {
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        int d = gmm.getD();
        
        int component = sampleCategorical(weights);
        return sampleGaussian(means[component], covariances[component], d);
    }
    
    private static int sampleCategorical(double[] weights) {
        double u = random.nextDouble();
        double cumsum = 0.0;
        for (int i = 0; i < weights.length; i++) {
            cumsum += weights[i];
            if (u <= cumsum) return i;
        }
        return weights.length - 1;
    }
    
    private static double[] sampleGaussian(double[] mean, double[][] covariance, int d) {
        double[] z = new double[d];
        for (int i = 0; i < d; i++) {
            z[i] = random.nextGaussian();
        }
        
        double[] result = new double[d];
        result[0] = mean[0] + Math.sqrt(covariance[0][0]) * z[0];
        return result;
    }
    
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
        
        double sum = 0.0;
        for (int k = 0; k < K; k++) {
            sum += Math.exp(logComps[k] - maxLogComp);
        }
        
        return maxLogComp + Math.log(sum);
    }
    
    private static double logGaussianPDF(double[] x, double[] mean, double[][] cov) {
        double diff = x[0] - mean[0];
        double var = cov[0][0];
        double mahal = diff * diff / var;
        return -0.5 * (Math.log(2 * Math.PI) + Math.log(var) + mahal);
    }
}
