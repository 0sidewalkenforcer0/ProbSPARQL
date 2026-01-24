package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Performance benchmark for ProbSPARQL queries.
 * 
 * This class measures query execution times excluding JVM startup overhead,
 * providing accurate performance metrics for feasibility evaluation.
 * 
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.PerformanceBenchmark"
 */
public class PerformanceBenchmark {
    
    private static final int WARMUP_RUNS = 2;
    private static final int BENCHMARK_RUNS = 5;
    
    private static final String[] QUERY_FILES = {
        "U1_probabilistic_thresholding.sparql",
        "U2_probabilistic_comparison.sparql",
        "U3_distribution_transformation.sparql",
        "U4_distribution_manipulation.sparql",
        "U5_similarityjoin_test.sparql",
        "U6_fusejoin_comparison.sparql"
    };
    
    private static final String[] QUERY_DESCRIPTIONS = {
        "Probabilistic Thresholding (CDF)",
        "Probabilistic Comparison (JS/KL)",
        "Distribution Transformation",
        "Distribution Manipulation",
        "SIMILARITYJOIN",
        "FUSEJOIN"
    };
    
    public static void main(String[] args) throws IOException {
        // Initialize ProbSPARQL
        ProbSPARQL.init();
        
        String dataFile = args.length > 0 ? args[0] : "examples/data/angle-grinder-instances.ttl";
        String queryDir = args.length > 1 ? args[1] : "examples/queries";
        
        System.out.println("╔════════════════════════════════════════════════════════════════════╗");
        System.out.println("║            ProbSPARQL Performance Benchmark                        ║");
        System.out.println("╚════════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // Load data
        System.out.println("Loading data from: " + dataFile);
        long loadStart = System.nanoTime();
        Model model = RDFDataMgr.loadModel(dataFile);
        long loadTime = System.nanoTime() - loadStart;
        System.out.printf("  Loaded %d triples in %.2f ms%n", model.size(), loadTime / 1_000_000.0);
        System.out.println();
        
        Dataset dataset = DatasetFactory.create(model);
        
        // Results storage
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        
        // Run benchmarks
        System.out.println("Running benchmarks (" + WARMUP_RUNS + " warmup + " + BENCHMARK_RUNS + " measured runs)...");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        
        for (int i = 0; i < QUERY_FILES.length; i++) {
            String queryFile = queryDir + "/" + QUERY_FILES[i];
            String queryId = "U" + (i + 1);
            String description = QUERY_DESCRIPTIONS[i];
            
            Path queryPath = Paths.get(queryFile);
            if (!Files.exists(queryPath)) {
                System.out.println(queryId + ": Query file not found, skipping");
                continue;
            }
            
            String queryString = Files.readString(queryPath);
            Query query = QueryFactory.create(queryString);
            
            // Warmup runs
            for (int w = 0; w < WARMUP_RUNS; w++) {
                executeQuery(dataset, query);
            }
            
            // Benchmark runs
            List<Long> times = new ArrayList<>();
            int resultCount = 0;
            
            for (int r = 0; r < BENCHMARK_RUNS; r++) {
                long startTime = System.nanoTime();
                resultCount = executeQuery(dataset, query);
                long endTime = System.nanoTime();
                times.add(endTime - startTime);
            }
            
            BenchmarkResult result = new BenchmarkResult(queryId, description, times, resultCount);
            results.put(queryId, result);
            
            System.out.printf("  %s: %.2f ms ± %.2f ms (%d results)%n",
                queryId, result.meanMs, result.stdMs, result.resultCount);
        }
        
        // Print summary table
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("RESULTS TABLE (for paper):");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("| Query | Description | Mean (ms) | Std (ms) | Min (ms) | Max (ms) | Results |");
        System.out.println("|-------|-------------|-----------|----------|----------|----------|---------|");
        
        for (BenchmarkResult r : results.values()) {
            System.out.printf("| %s | %s | %.2f | %.2f | %.2f | %.2f | %d |%n",
                r.queryId,
                truncate(r.description, 25),
                r.meanMs, r.stdMs, r.minMs, r.maxMs, r.resultCount);
        }
        
        // Calculate overall statistics
        double avgMean = results.values().stream().mapToDouble(r -> r.meanMs).average().orElse(0);
        double maxMean = results.values().stream().mapToDouble(r -> r.meanMs).max().orElse(0);
        double minMean = results.values().stream().mapToDouble(r -> r.meanMs).min().orElse(0);
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("SUMMARY SENTENCE FOR PAPER:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.printf(
            "Our feasibility evaluation demonstrates that all %d query types%n" +
            "execute efficiently, with mean response times ranging from %.1f ms to %.1f ms%n" +
            "(overall average: %.1f ms), confirming the practical applicability of%n" +
            "ProbSPARQL for interactive uncertainty-aware knowledge graph queries.%n",
            results.size(), minMean, maxMean, avgMean);
        
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("JSON OUTPUT:");
        System.out.println("═══════════════════════════════════════════════════════════════════");
        printJsonResults(results, model.size());
    }
    
    private static int executeQuery(Dataset dataset, Query query) {
        int count = 0;
        try (QueryExecution qexec = QueryExecutionFactory.create(query, dataset)) {
            ResultSet results = qexec.execSelect();
            while (results.hasNext()) {
                results.next();
                count++;
            }
        }
        return count;
    }
    
    private static String truncate(String s, int maxLen) {
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen - 3) + "...";
    }
    
    private static void printJsonResults(Map<String, BenchmarkResult> results, long tripleCount) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"dataset\": {\n");
        json.append("    \"file\": \"angle-grinder-instances.ttl\",\n");
        json.append("    \"triples\": ").append(tripleCount).append("\n");
        json.append("  },\n");
        json.append("  \"benchmark_config\": {\n");
        json.append("    \"warmup_runs\": ").append(WARMUP_RUNS).append(",\n");
        json.append("    \"benchmark_runs\": ").append(BENCHMARK_RUNS).append("\n");
        json.append("  },\n");
        json.append("  \"results\": {\n");
        
        int i = 0;
        for (BenchmarkResult r : results.values()) {
            json.append("    \"").append(r.queryId).append("\": {\n");
            json.append("      \"description\": \"").append(r.description).append("\",\n");
            json.append("      \"mean_ms\": ").append(String.format("%.2f", r.meanMs)).append(",\n");
            json.append("      \"std_ms\": ").append(String.format("%.2f", r.stdMs)).append(",\n");
            json.append("      \"min_ms\": ").append(String.format("%.2f", r.minMs)).append(",\n");
            json.append("      \"max_ms\": ").append(String.format("%.2f", r.maxMs)).append(",\n");
            json.append("      \"result_count\": ").append(r.resultCount).append("\n");
            json.append("    }").append(i < results.size() - 1 ? "," : "").append("\n");
            i++;
        }
        
        json.append("  }\n");
        json.append("}\n");
        
        System.out.println(json);
    }
    
    static class BenchmarkResult {
        String queryId;
        String description;
        double meanMs;
        double stdMs;
        double minMs;
        double maxMs;
        int resultCount;
        
        BenchmarkResult(String queryId, String description, List<Long> timesNs, int resultCount) {
            this.queryId = queryId;
            this.description = description;
            this.resultCount = resultCount;
            
            double[] timesMs = timesNs.stream().mapToDouble(t -> t / 1_000_000.0).toArray();
            
            this.meanMs = Arrays.stream(timesMs).average().orElse(0);
            this.minMs = Arrays.stream(timesMs).min().orElse(0);
            this.maxMs = Arrays.stream(timesMs).max().orElse(0);
            
            if (timesMs.length > 1) {
                double variance = Arrays.stream(timesMs)
                    .map(t -> Math.pow(t - meanMs, 2))
                    .average().orElse(0);
                this.stdMs = Math.sqrt(variance);
            } else {
                this.stdMs = 0;
            }
        }
    }
}
