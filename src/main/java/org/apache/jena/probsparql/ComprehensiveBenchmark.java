package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;
import java.io.StringReader;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Comprehensive benchmark showing all query types with different configurations.
 */
public class ComprehensiveBenchmark {
    
    private static final String PREFIXES = """
        PREFIX ex: <http://example.org/>
        PREFIX prob: <http://probsparql.org/function#>
        PREFIX xsd: <http://www.w3.org/2001/XMLSchema#>
        """;
    
    public static void main(String[] args) {
        // Register ProbSPARQL
        ProbSPARQL.init();
        
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║       Comprehensive ProbSPARQL Performance Benchmark               ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Test configurations
        int[] entityCounts = {100, 500, 1000, 2000};
        int[] kValues = {1, 2, 3, 5};
        
        // ═══════════════════════════════════════════════════════════════════
        // PART 1: Data Size Scalability (K=2 fixed)
        // ═══════════════════════════════════════════════════════════════════
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("PART 1: DATA SIZE SCALABILITY (K=2 fixed)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        double[][] sizeResults = new double[entityCounts.length][6];
        
        for (int e = 0; e < entityCounts.length; e++) {
            int numEntities = entityCounts[e];
            System.out.println("--- " + numEntities + " entities ---");
            
            Model model = generateData(numEntities, 2);
            double[] times = runAllQueries(model, numEntities);
            sizeResults[e] = times;
            
            System.out.printf("  U1-CDF:        %8.2f ms%n", times[0]);
            System.out.printf("  U2-Stats:      %8.2f ms%n", times[1]);
            System.out.printf("  U3-PDF:        %8.2f ms%n", times[2]);
            System.out.printf("  U4-Transform:  %8.2f ms%n", times[3]);
            System.out.printf("  U5-Divergence: %8.2f ms%n", times[4]);
            System.out.printf("  U6-Fuse:       %8.2f ms%n", times[5]);
            System.out.println();
        }
        
        // Print size scalability table
        System.out.println("DATA SIZE SCALABILITY TABLE (K=2):");
        System.out.println("| Entities | U1-CDF (ms) | U2-Stats (ms) | U3-PDF (ms) | U4-Transform (ms) | U5-Divergence (ms) | U6-Fuse (ms) |");
        System.out.println("|----------|-------------|---------------|-------------|-------------------|--------------------|--------------| ");
        for (int e = 0; e < entityCounts.length; e++) {
            System.out.printf("| %8d | %11.2f | %13.2f | %11.2f | %17.2f | %18.2f | %12.2f |%n",
                entityCounts[e], sizeResults[e][0], sizeResults[e][1], sizeResults[e][2],
                sizeResults[e][3], sizeResults[e][4], sizeResults[e][5]);
        }
        System.out.println();
        
        // ═══════════════════════════════════════════════════════════════════
        // PART 2: GMM Complexity Impact (500 entities fixed)
        // ═══════════════════════════════════════════════════════════════════
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("PART 2: GMM COMPLEXITY IMPACT (500 entities fixed)");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        double[][] kResults = new double[kValues.length][6];
        
        for (int k = 0; k < kValues.length; k++) {
            int K = kValues[k];
            System.out.println("--- K=" + K + " components ---");
            
            Model model = generateData(500, K);
            double[] times = runAllQueries(model, 500);
            kResults[k] = times;
            
            System.out.printf("  U1-CDF:        %8.2f ms%n", times[0]);
            System.out.printf("  U2-Stats:      %8.2f ms%n", times[1]);
            System.out.printf("  U3-PDF:        %8.2f ms%n", times[2]);
            System.out.printf("  U4-Transform:  %8.2f ms%n", times[3]);
            System.out.printf("  U5-Divergence: %8.2f ms%n", times[4]);
            System.out.printf("  U6-Fuse:       %8.2f ms%n", times[5]);
            System.out.println();
        }
        
        // Print K complexity table
        System.out.println("GMM COMPLEXITY TABLE (500 entities):");
        System.out.println("| K | U1-CDF (ms) | U2-Stats (ms) | U3-PDF (ms) | U4-Transform (ms) | U5-Divergence (ms) | U6-Fuse (ms) |");
        System.out.println("|---|-------------|---------------|-------------|-------------------|--------------------|--------------| ");
        for (int k = 0; k < kValues.length; k++) {
            System.out.printf("| %d | %11.2f | %13.2f | %11.2f | %17.2f | %18.2f | %12.2f |%n",
                kValues[k], kResults[k][0], kResults[k][1], kResults[k][2],
                kResults[k][3], kResults[k][4], kResults[k][5]);
        }
        System.out.println();
        
        // ═══════════════════════════════════════════════════════════════════
        // SUMMARY
        // ═══════════════════════════════════════════════════════════════════
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY FOR PAPER:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        
        System.out.println("Query Type Categories:");
        System.out.println("  - Filter queries (U1-U4): Single-entity operations, O(n) complexity");
        System.out.println("  - Join queries (U5-U6): Pairwise operations, O(n²) complexity");
        System.out.println();
        
        // Size scalability summary
        System.out.println("Data Size Impact (100 → 2000 entities, 20x increase):");
        for (int q = 0; q < 6; q++) {
            String[] names = {"U1-CDF", "U2-Stats", "U3-PDF", "U4-Transform", "U5-Divergence", "U6-Fuse"};
            double ratio = sizeResults[3][q] / sizeResults[0][q];
            System.out.printf("  %s: %.2f ms → %.2f ms (%.1fx)%n", 
                names[q], sizeResults[0][q], sizeResults[3][q], ratio);
        }
        System.out.println();
        
        // K complexity summary
        System.out.println("GMM Complexity Impact (K=1 → K=5):");
        for (int q = 0; q < 6; q++) {
            String[] names = {"U1-CDF", "U2-Stats", "U3-PDF", "U4-Transform", "U5-Divergence", "U6-Fuse"};
            double ratio = kResults[3][q] / kResults[0][q];
            System.out.printf("  %s: %.2f ms → %.2f ms (%.1fx)%n", 
                names[q], kResults[0][q], kResults[3][q], ratio);
        }
    }
    
    private static double[] runAllQueries(Model model, int numEntities) {
        double[] times = new double[6];
        int warmup = 2;
        int runs = 3;
        
        // U1: CDF-based filtering
        String queryU1 = PREFIXES + """
            SELECT ?sensor ?prob WHERE {
                ?sensor ex:hasMeasurement ?m .
                ?m ex:hasValue ?gmm .
                BIND(prob:cdf(?gmm, 10.0) AS ?prob)
                FILTER(?prob > 0.5)
            }
            """;
        times[0] = benchmarkQuery(model, queryU1, warmup, runs);
        
        // U2: Statistical extraction
        String queryU2 = PREFIXES + """
            SELECT ?sensor ?mean ?variance WHERE {
                ?sensor ex:hasMeasurement ?m .
                ?m ex:hasValue ?gmm .
                BIND(prob:mean(?gmm) AS ?mean)
                BIND(prob:variance(?gmm) AS ?variance)
            }
            """;
        times[1] = benchmarkQuery(model, queryU2, warmup, runs);
        
        // U3: PDF evaluation
        String queryU3 = PREFIXES + """
            SELECT ?sensor ?density WHERE {
                ?sensor ex:hasMeasurement ?m .
                ?m ex:hasValue ?gmm .
                BIND(prob:pdf(?gmm, 10.0) AS ?density)
            }
            """;
        times[2] = benchmarkQuery(model, queryU3, warmup, runs);
        
        // U4: Distribution transformation
        String queryU4 = PREFIXES + """
            SELECT ?sensor ?transformed WHERE {
                ?sensor ex:hasMeasurement ?m .
                ?m ex:hasValue ?gmm .
                BIND(prob:scale(?gmm, 2.0) AS ?transformed)
            }
            LIMIT 100
            """;
        times[3] = benchmarkQuery(model, queryU4, warmup, runs);
        
        // U5: Similarity join with JS divergence
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
        times[4] = benchmarkQuery(model, queryU5, warmup, runs);
        
        // U6: Fuse join
        String queryU6 = PREFIXES + """
            SELECT ?s1 ?s2 ?fused WHERE {
                ?s1 ex:hasMeasurement ?m1 .
                ?m1 ex:hasValue ?gmm1 .
                ?s2 ex:hasMeasurement ?m2 .
                ?m2 ex:hasValue ?gmm2 .
                FILTER(?s1 < ?s2)
                BIND(prob:fuse(?gmm1, ?gmm2) AS ?fused)
            }
            LIMIT 100
            """;
        times[5] = benchmarkQuery(model, queryU6, warmup, runs);
        
        return times;
    }
    
    private static double benchmarkQuery(Model model, String queryStr, int warmup, int runs) {
        Query query = QueryFactory.create(queryStr);
        
        // Warmup
        for (int i = 0; i < warmup; i++) {
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) rs.next();
            }
        }
        
        // Measure
        long totalTime = 0;
        for (int i = 0; i < runs; i++) {
            long start = System.nanoTime();
            try (QueryExecution qe = QueryExecutionFactory.create(query, model)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) rs.next();
            }
            totalTime += System.nanoTime() - start;
        }
        
        return totalTime / 1_000_000.0 / runs;
    }
    
    private static Model generateData(int numEntities, int k) {
        StringBuilder ttl = new StringBuilder();
        ttl.append("@prefix ex: <http://example.org/> .\n");
        ttl.append("@prefix xsd: <http://www.w3.org/2001/XMLSchema#> .\n");
        ttl.append("@prefix prob: <http://probsparql.org/> .\n\n");
        
        for (int i = 0; i < numEntities; i++) {
            String gmmLiteral = generateGMMLiteral(k);
            ttl.append(String.format("ex:sensor%d ex:hasMeasurement ex:measurement%d .%n", i, i));
            ttl.append(String.format("ex:measurement%d ex:hasValue \"%s\"^^<http://example.org/ontology/uncertainty#gmmLiteral> .%n", 
                i, gmmLiteral.replace("\"", "\\\"")));
        }
        
        Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new StringReader(ttl.toString()), "", Lang.TURTLE);
        return model;
    }
    
    private static String generateGMMLiteral(int k) {
        ThreadLocalRandom rand = ThreadLocalRandom.current();
        
        StringBuilder sb = new StringBuilder();
        sb.append("{\"K\":").append(k);
        sb.append(",\"d\":1,\"covariance_type\":\"full\"");
        
        // Weights
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
        
        // Means
        sb.append(",\"means\":[");
        for (int i = 0; i < k; i++) {
            sb.append("[").append(rand.nextDouble(5.0, 15.0)).append("]");
            if (i < k - 1) sb.append(",");
        }
        sb.append("]");
        
        // Covariances
        sb.append(",\"covariances\":[");
        for (int i = 0; i < k; i++) {
            sb.append("[[").append(rand.nextDouble(0.1, 2.0)).append("]]");
            if (i < k - 1) sb.append(",");
        }
        sb.append("]}");
        
        return sb.toString();
    }
}
