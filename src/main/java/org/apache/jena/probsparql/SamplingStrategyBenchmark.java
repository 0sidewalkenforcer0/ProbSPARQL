package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.util.FileManager;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Sampling Strategy Benchmark
 * 
 * Compares different JSD sampling strategies:
 * - GT_100, GT_1K, GT_5K, GT_10K (Ground Truth with different sample counts)
 * - V1_MC (Pure Monte Carlo)
 * - V2_STRATIFIED (Stratified Sampling)
 * - V3_SPRT (SPRT Early Termination)
 * - V4_BOUNDS (Bounds Filter)
 * - V5_ADAPTIVE (Complete Solution: Bounds + SPRT + Stratified)
 * 
 * Usage:
 *   java -Dprobsparql.mode=V5_ADAPTIVE -jar probsparql.jar \
 *       --dataset benchmark/data/benchmark_JSD_mixed.ttl \
 *       --queries benchmark/queries/JSD_strict.sparql \
 *       --output benchmark_results.json
 * 
 * @author ProbSPARQL Team
 */
public class SamplingStrategyBenchmark {
    
    private static final String[] MODES = {
        "GT_100", "GT_1K", "GT_5K", "GT_10K",
        "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"
    };
    
    private static final String[] QUERIES = {
        "benchmark/queries/JSD_strict.sparql",
        "benchmark/queries/JSD_medium.sparql",
        "benchmark/queries/JSD_loose.sparql"
    };
    
    public static void main(String[] args) throws Exception {
        String datasetPath = "benchmark/data/benchmark_JSD_mixed.ttl";
        String outputPath = "benchmark_results.json";
        String queryMode = null;
        int iterations = 3;
        
        for (int i = 0; i < args.length; i++) {
            if ("--dataset".equals(args[i]) && i + 1 < args.length) {
                datasetPath = args[i + 1];
            } else if ("--output".equals(args[i]) && i + 1 < args.length) {
                outputPath = args[i + 1];
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                queryMode = args[i + 1];
            } else if ("--iterations".equals(args[i]) && i + 1 < args.length) {
                iterations = Integer.parseInt(args[i + 1]);
            }
        }
        
        JSDivergenceConfig.printConfig();
        
        System.out.println("Loading dataset: " + datasetPath);
        Model model = loadDataset(datasetPath);
        System.out.println("Loaded " + model.size() + " triples");
        
        if (queryMode != null) {
            runSingleMode(model, queryMode, outputPath, iterations);
        } else {
            runAllModes(model, outputPath, iterations);
        }
    }
    
    private static Model loadDataset(String path) {
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = FileManager.get().open(path)) {
            if (in == null) {
                throw new FileNotFoundException("Dataset not found: " + path);
            }
            model.read(in, null, "TTL");
        } catch (Exception e) {
            System.err.println("Error loading dataset: " + e.getMessage());
            System.exit(1);
        }
        return model;
    }
    
    private static String loadQuery(String path) {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
        } catch (Exception e) {
            System.err.println("Error loading query: " + e.getMessage());
            System.exit(1);
        }
        return sb.toString();
    }
    
    private static void runSingleMode(Model model, String mode, String outputPath, int iterations) {
        System.out.println("\n=== Running mode: " + mode + " ===");
        
        System.setProperty("probsparql.mode", mode);
        
        Map<String, Object> results = new HashMap<>();
        results.put("mode", mode);
        results.put("timestamp", new Date().toString());
        results.put("iterations", iterations);
        results.put("sample_count", JSDivergenceConfig.getSampleCount());
        
        Map<String, List<Double>> queryTimes = new LinkedHashMap<>();
        
        for (String queryPath : QUERIES) {
            String queryStr = loadQuery(queryPath);
            String queryName = new File(queryPath).getName().replace(".sparql", "");
            
            List<Double> times = new ArrayList<>();
            List<Result> queryResults = new ArrayList<>();
            
            for (int i = 0; i < iterations; i++) {
                long startTime = System.nanoTime();
                Result result = executeQuery(model, queryStr);
                long endTime = System.nanoTime();
                
                double elapsedMs = (endTime - startTime) / 1_000_000.0;
                times.add(elapsedMs);
                if (i == 0) {
                    queryResults.add(result);
                }
            }
            
            double avgTime = times.stream().mapToDouble(d -> d).average().orElse(0);
            double minTime = times.stream().mapToDouble(d -> d).min().orElse(0);
            double maxTime = times.stream().mapToDouble(d -> d).max().orElse(0);
            
            Map<String, Object> queryData = new HashMap<>();
            queryData.put("avg_time_ms", avgTime);
            queryData.put("min_time_ms", minTime);
            queryData.put("max_time_ms", maxTime);
            queryData.put("result_count", queryResults.isEmpty() ? 0 : queryResults.get(0).count);
            
            results.put(queryName, queryData);
            queryTimes.put(queryName, times);
            
            System.out.printf("  %s: %.2f ms (avg), %d results%n", 
                queryName, avgTime, queryResults.isEmpty() ? 0 : queryResults.get(0).count);
        }
        
        saveResults(results, outputPath);
    }
    
    private static void runAllModes(Model model, String outputPath, int iterations) {
        Map<String, Map<String, Object>> allResults = new LinkedHashMap<>();
        
        for (String mode : MODES) {
            System.out.println("\n=== Running mode: " + mode + " ===");
            
            System.setProperty("probsparql.mode", mode);
            
            Map<String, Object> modeResults = new HashMap<>();
            modeResults.put("mode", mode);
            modeResults.put("sample_count", JSDivergenceConfig.getSampleCount());
            
            for (String queryPath : QUERIES) {
                String queryStr = loadQuery(queryPath);
                String queryName = new File(queryPath).getName().replace(".sparql", "");
                
                List<Double> times = new ArrayList<>();
                int resultCount = 0;
                
                for (int i = 0; i < iterations; i++) {
                    long startTime = System.nanoTime();
                    Result result = executeQuery(model, queryStr);
                    long endTime = System.nanoTime();
                    
                    double elapsedMs = (endTime - startTime) / 1_000_000.0;
                    times.add(elapsedMs);
                    if (i == 0) {
                        resultCount = result.count;
                    }
                }
                
                double avgTime = times.stream().mapToDouble(d -> d).average().orElse(0);
                
                Map<String, Object> queryData = new HashMap<>();
                queryData.put("avg_time_ms", avgTime);
                queryData.put("result_count", resultCount);
                
                modeResults.put(queryName, queryData);
                
                System.out.printf("  %s: %.2f ms (%d results)%n", 
                    queryName, avgTime, resultCount);
            }
            
            allResults.put(mode, modeResults);
        }
        
        Map<String, Object> summary = new HashMap<>();
        summary.put("timestamp", new Date().toString());
        summary.put("iterations", iterations);
        summary.put("results", allResults);
        
        saveResults(summary, outputPath);
        printSummary(allResults);
    }
    
    private static Result executeQuery(Model model, String queryStr) {
        Result result = new Result();
        
        try (QueryExecution qexec = QueryExecutionFactory.create(queryStr, model)) {
            Query query = qexec.getQuery();
            
            ResultSet rs = qexec.execSelect();
            int count = 0;
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
            result.count = count;
        } catch (Exception e) {
            System.err.println("Query error: " + e.getMessage());
            result.count = 0;
        }
        
        return result;
    }
    
    private static void saveResults(Map<String, Object> results, String outputPath) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(outputPath))) {
            writer.println("{");
            int i = 0;
            for (Map.Entry<String, Object> entry : results.entrySet()) {
                if (i > 0) writer.println(",");
                writer.print("  \"" + entry.getKey() + "\": ");
                writer.print(entry.getValue() instanceof Map ? 
                    mapToJson((Map<?, ?>) entry.getValue(), 2) : 
                    "\"" + entry.getValue() + "\"");
                i++;
            }
            writer.println("\n}");
        } catch (Exception e) {
            System.err.println("Error saving results: " + e.getMessage());
        }
    }
    
    private static String mapToJson(Map<?, ?> map, int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        String prefix = "    ".repeat(indent);
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (i > 0) sb.append(",\n");
            sb.append(prefix).append("\"").append(entry.getKey()).append("\": ");
            if (entry.getValue() instanceof Map) {
                sb.append(mapToJson((Map<?, ?>) entry.getValue(), indent + 1));
            } else if (entry.getValue() instanceof Number) {
                sb.append(entry.getValue());
            } else {
                sb.append("\"").append(entry.getValue()).append("\"");
            }
            i++;
        }
        sb.append("\n").append("    ".repeat(indent - 1)).append("}");
        return sb.toString();
    }
    
    private static void printSummary(Map<String, Map<String, Object>> allResults) {
        System.out.println("\n=== SUMMARY ===");
        System.out.printf("%-15s | %-12s | %-12s | %-12s%n", 
            "Mode", "JSD_strict", "JSD_medium", "JSD_loose");
        System.out.println("-".repeat(60));
        
        for (Map.Entry<String, Map<String, Object>> modeEntry : allResults.entrySet()) {
            String mode = modeEntry.getKey();
            Map<String, Object> queries = modeEntry.getValue();
            
            double strict = getQueryTime(queries, "JSD_strict");
            double medium = getQueryTime(queries, "JSD_medium");
            double loose = getQueryTime(queries, "JSD_loose");
            
            System.out.printf("%-15s | %10.2fms | %10.2fms | %10.2fms%n", 
                mode, strict, medium, loose);
        }
    }
    
    private static double getQueryTime(Map<String, Object> queries, String name) {
        Object q = queries.get(name);
        if (q instanceof Map) {
            Object t = ((Map<?, ?>) q).get("avg_time_ms");
            if (t instanceof Number) {
                return ((Number) t).doubleValue();
            }
        }
        return 0;
    }
    
    private static class Result {
        int count = 0;
    }
}
