package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import org.apache.jena.probsparql.datatypes.GMMValue;
import java.io.StringReader;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test U5 query with 5000 entities and different sampling sizes.
 */
public class U5SamplingTest {
    
    private static final String PREFIXES = """
        PREFIX ex: <http://example.org/>
        PREFIX prob: <http://probsparql.org/function#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;
    
    private static final Random random = new Random(42);
    private static final int NUM_ENTITIES = 5000;
    private static final int K = 2;
    
    public static void main(String[] args) {
        ProbSPARQL.init();
        
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║   U5 Similarity Join: Sampling Size Impact (5000 entities, K=2)   ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        int[] samplingCounts = {1000, 5000, 10000, 20000};
        
        // Generate 5000 GMM pairs for testing
        System.out.println("Generating " + NUM_ENTITIES + " GMM distributions...");
        GMMValue[] gmms = new GMMValue[NUM_ENTITIES];
        for (int i = 0; i < NUM_ENTITIES; i++) {
            gmms[i] = generateGMM(K);
        }
        System.out.println("Done.");
        System.out.println();
        
        // The U5 query does pairwise comparison, so with LIMIT 100 it does ~100 comparisons
        // But the full query without limit would do n*(n-1)/2 comparisons
        // For 5000 entities: 5000*4999/2 = 12,497,500 pairs
        
        // We'll test two scenarios:
        // 1. Per-pair divergence time (to show sampling impact)
        // 2. Estimated total query time for LIMIT 100
        
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("TEST 1: Per-pair JS Divergence Computation Time");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        int numTestPairs = 100;
        double[] perPairTimes = new double[samplingCounts.length];
        
        for (int s = 0; s < samplingCounts.length; s++) {
            int samples = samplingCounts[s];
            
            // Warmup
            for (int i = 0; i < 5; i++) {
                computeJSDivergence(gmms[0], gmms[1], samples);
            }
            
            // Measure
            long start = System.nanoTime();
            for (int i = 0; i < numTestPairs; i++) {
                int idx1 = i % NUM_ENTITIES;
                int idx2 = (i + 1) % NUM_ENTITIES;
                computeJSDivergence(gmms[idx1], gmms[idx2], samples);
            }
            long elapsed = System.nanoTime() - start;
            perPairTimes[s] = elapsed / 1_000_000.0 / numTestPairs;
            
            System.out.printf("  %5d samples: %.3f ms per pair%n", samples, perPairTimes[s]);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("TEST 2: Estimated U5 Query Time (LIMIT 100 pairs evaluated)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        // With LIMIT 100 and filtering, we estimate ~100-200 pairs need to be computed
        int estimatedPairsForLimit100 = 150;
        
        System.out.println("Assumption: ~" + estimatedPairsForLimit100 + " divergence computations for LIMIT 100");
        System.out.println();
        
        for (int s = 0; s < samplingCounts.length; s++) {
            double estimatedTime = perPairTimes[s] * estimatedPairsForLimit100;
            System.out.printf("  %5d samples: %.1f ms (estimated)%n", samplingCounts[s], estimatedTime);
        }
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("TEST 3: Actual U5 Query Execution (default 1000 samples)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        // Generate RDF model with 5000 entities
        System.out.println("Building RDF model with " + NUM_ENTITIES + " entities...");
        Model model = generateData(NUM_ENTITIES, K);
        System.out.println("Model size: " + model.size() + " triples");
        System.out.println();
        
        // Run actual U5 query
        String queryU5 = PREFIXES + """
            SELECT ?s1 ?s2 ?divergence WHERE {
                ?s1 ex:hasMeasurement ?m1 .
                ?m1 ex:hasValue ?gmm1 .
                ?s2 ex:hasMeasurement ?m2 .
                ?m2 ex:hasValue ?gmm2 .
                FILTER(?s1 < ?s2)
                BIND(prob:jsdivergence(?gmm1, ?gmm2) AS ?divergence)
                FILTER(?divergence < 0.5)
            }
            LIMIT 100
            """;
        
        System.out.println("Running U5 query with LIMIT 100...");
        
        // Warmup
        Query query = QueryFactory.create(queryU5);
        for (int i = 0; i < 2; i++) {
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) rs.next();
            }
        }
        
        // Measure (3 runs)
        long totalTime = 0;
        int resultCount = 0;
        for (int i = 0; i < 3; i++) {
            long start = System.nanoTime();
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    rs.next();
                    if (i == 0) resultCount++;
                }
            }
            totalTime += System.nanoTime() - start;
        }
        double avgTime = totalTime / 3.0 / 1_000_000.0;
        
        System.out.printf("  Actual U5 time (1000 samples): %.2f ms (%d results)%n", avgTime, resultCount);
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS SUMMARY TABLE");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("┌─────────────┬───────────────────┬────────────────────┬────────────────┐");
        System.out.println("│ Samples     │ Per-pair time(ms) │ Est. query time(ms)│ Ratio vs 1000  │");
        System.out.println("├─────────────┼───────────────────┼────────────────────┼────────────────┤");
        for (int s = 0; s < samplingCounts.length; s++) {
            double estimatedTime = perPairTimes[s] * estimatedPairsForLimit100;
            double ratio = perPairTimes[s] / perPairTimes[0];
            System.out.printf("│ %11d │ %17.3f │ %18.1f │ %14.2fx │%n", 
                samplingCounts[s], perPairTimes[s], estimatedTime, ratio);
        }
        System.out.println("└─────────────┴───────────────────┴────────────────────┴────────────────┘");
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("CONCLUSION FOR PAPER:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.printf("With 5,000 entities and K=2 GMM components:%n");
        System.out.printf("  - 1,000 samples:  %.3f ms per divergence computation%n", perPairTimes[0]);
        System.out.printf("  - 5,000 samples:  %.3f ms per divergence computation (%.1fx)%n", 
            perPairTimes[1], perPairTimes[1]/perPairTimes[0]);
        System.out.printf("  - 10,000 samples: %.3f ms per divergence computation (%.1fx)%n", 
            perPairTimes[2], perPairTimes[2]/perPairTimes[0]);
        System.out.printf("  - 20,000 samples: %.3f ms per divergence computation (%.1fx)%n", 
            perPairTimes[3], perPairTimes[3]/perPairTimes[0]);
        System.out.println();
        System.out.println("The sampling count shows approximately linear scaling with computation time.");
    }
    
    // ═══════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════════
    
    private static Model generateData(int numEntities, int k) {
        StringBuilder ttl = new StringBuilder();
        ttl.append("@prefix ex: <http://example.org/> .\n");
        ttl.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n\n");
        
        for (int i = 0; i < numEntities; i++) {
            String gmmLiteral = generateGMMLiteralString(k);
            ttl.append(String.format("ex:sensor%d ex:hasMeasurement ex:measurement%d .%n", i, i));
            ttl.append(String.format("ex:measurement%d ex:hasValue \"%s\"^^<http://example.org/ontology/uncertainty#gmmLiteral> .%n", 
                i, gmmLiteral.replace("\"", "\\\"")));
        }
        
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(ttl.toString()), "", Lang.TURTLE);
        return model;
    }
    
    private static String generateGMMLiteralString(int k) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"K\":").append(k);
        sb.append(",\"d\":1,\"covariance_type\":\"full\"");
        
        double[] weights = new double[k];
        double sum = 0;
        for (int i = 0; i < k; i++) {
            weights[i] = rand.nextDouble(0.1, 1.0);
            sum += weights[i];
        }
        sb.append(",\"weights\":[");
        for (int i = 0; i < k; i++) {
            sb.append(weights[i] / sum);
            if (i < k - 1) sb.append(",");
        }
        sb.append("]");
        
        sb.append(",\"means\":[");
        for (int i = 0; i < k; i++) {
            sb.append("[").append(rand.nextDouble(5.0, 15.0)).append("]");
            if (i < k - 1) sb.append(",");
        }
        sb.append("]");
        
        sb.append(",\"covariances\":[");
        for (int i = 0; i < k; i++) {
            sb.append("[[").append(rand.nextDouble(0.1, 2.0)).append("]]");
            if (i < k - 1) sb.append(",");
        }
        sb.append("]}");
        
        return sb.toString();
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
    
    // ═══════════════════════════════════════════════════════════════════
    // JS Divergence computation
    // ═══════════════════════════════════════════════════════════════════
    
    private static double computeJSDivergence(GMMValue p, GMMValue q, int numSamples) {
        GMMValue m = createMixture(p, q);
        double klPM = computeKLDivergence(p, m, numSamples / 2);
        double klQM = computeKLDivergence(q, m, numSamples / 2);
        return 0.5 * klPM + 0.5 * klQM;
    }
    
    private static GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getNComponents();
        int kQ = q.getNComponents();
        int newK = kP + kQ;
        int d = p.getDimensions();
        
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
        int d = gmm.getDimensions();
        
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
        if (d == 1) {
            result[0] = mean[0] + Math.sqrt(covariance[0][0]) * z[0];
        } else {
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
    
    private static double computeLogPDF(GMMValue gmm, double[] x) {
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        int K = gmm.getNComponents();
        
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
        int d = x.length;
        double[] diff = new double[d];
        for (int i = 0; i < d; i++) {
            diff[i] = x[i] - mean[i];
        }
        
        if (d == 1) {
            double var = cov[0][0];
            double mahal = diff[0] * diff[0] / var;
            return -0.5 * (Math.log(2 * Math.PI) + Math.log(var) + mahal);
        }
        
        double logDet = 0.0;
        double mahal = 0.0;
        for (int i = 0; i < d; i++) {
            logDet += Math.log(cov[i][i]);
            mahal += diff[i] * diff[i] / cov[i][i];
        }
        
        return -0.5 * (d * Math.log(2 * Math.PI) + logDet + mahal);
    }
}
