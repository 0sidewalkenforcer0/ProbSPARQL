package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.engine.QueryEngineProbabilistic;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.probsparql.functions.comparison.AdaptiveSampler;

import java.io.*;
import java.util.*;

/**
 * SimilarityJoin Performance Benchmark
 * 
 * Compares V1-V5 + GT_10K on four datasets (easy/medium/hard/mixed) using
 * the full DIVJOIN query path:
 *   JavaCC Parser → OpSimilarityJoin → QueryIterSimilarityJoin → JSDivergence
 *
 * Outputs:
 *   - benchmark/results/simjoin_results.csv
 *   - benchmark/results/simjoin_v5_breakdown.csv
 *
 * Usage:
 *   java -cp ... org.apache.jena.probsparql.SimilarityJoinBenchmark
 *        [--data-dir benchmark/data]
 *        [--query benchmark/queries/simjoin_benchmark.sparql]
 *        [--output-dir benchmark/results]
 *        [--warmup 2] [--iterations 5]
 */
public class SimilarityJoinBenchmark {

    private static final String[] MODES = {
        "GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"
    };

    private static final String[] DATASETS = {"easy", "medium", "hard", "mixed"};

    public static void main(String[] args) throws Exception {
        // Trigger ProbSPARQL initialization
        ProbSPARQL.init();

        String dataDir   = "benchmark/data";
        String queryPath = "benchmark/queries/simjoin_benchmark.sparql";
        String outputDir = "benchmark/results";
        int warmup     = 2;
        int iterations = 5;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir":   dataDir   = args[++i]; break;
                case "--query":      queryPath = args[++i]; break;
                case "--output-dir": outputDir = args[++i]; break;
                case "--warmup":     warmup     = Integer.parseInt(args[++i]); break;
                case "--iterations": iterations = Integer.parseInt(args[++i]); break;
            }
        }

        new File(outputDir).mkdirs();

        String queryString = loadFile(queryPath);
        System.out.println("=== SimilarityJoin Performance Benchmark ===");
        System.out.println("Datasets dir : " + dataDir);
        System.out.println("Query        : " + queryPath);
        System.out.println("Warmup       : " + warmup);
        System.out.println("Iterations   : " + iterations);
        System.out.println("Sample budget: 5000 (unified)");
        System.out.println();

        // CSV writers
        String resultsCsv = outputDir + "/simjoin_results.csv";
        String v5Csv      = outputDir + "/simjoin_v5_breakdown.csv";

        List<String[]> resultRows = new ArrayList<>();
        resultRows.add(new String[]{"Method", "Dataset", "Iteration", "Time_ms", "ResultCount"});

        List<String[]> v5Rows = new ArrayList<>();
        v5Rows.add(new String[]{"Dataset", "BoundsFiltered", "SPRTEarly", "FullSampling",
                                "BoundsTime_ms", "SPRTTime_ms", "StratifiedTime_ms"});

        // Run benchmark matrix
        for (String mode : MODES) {
            System.out.println("============================================");
            System.out.println("MODE: " + mode);
            System.out.println("============================================");

            // Switch mode
            System.setProperty("probsparql.mode", mode);
            JSDivergenceConfig.reloadMode();

            for (String dataset : DATASETS) {
                String ttlPath = dataDir + "/simjoin_" + dataset + ".ttl";
                File ttlFile = new File(ttlPath);
                if (!ttlFile.exists()) {
                    System.err.println("  SKIP: " + ttlPath + " not found");
                    continue;
                }

                System.out.println("  Dataset: " + dataset);

                // Load model
                Model model = ModelFactory.createDefaultModel();
                try (InputStream in = new FileInputStream(ttlFile)) {
                    model.read(in, null, "TTL");
                }
                System.out.println("    Loaded " + model.size() + " triples");

                // Warmup
                for (int w = 0; w < warmup; w++) {
                    executeSimJoinQuery(model, queryString);
                }
                System.out.println("    Warmup done (" + warmup + " runs)");

                // Timed iterations
                for (int it = 0; it < iterations; it++) {
                    long t0 = System.nanoTime();
                    int count = executeSimJoinQuery(model, queryString);
                    long t1 = System.nanoTime();
                    double ms = (t1 - t0) / 1_000_000.0;

                    resultRows.add(new String[]{
                        mode, dataset, String.valueOf(it + 1),
                        String.format("%.2f", ms), String.valueOf(count)
                    });

                    if (it == 0) {
                        System.out.printf("    Iter %d: %.2f ms, %d results%n", it + 1, ms, count);
                    }
                }

                // Collect V5 breakdown stats
                if ("V5_ADAPTIVE".equals(mode)) {
                    collectV5Stats(model, queryString, dataset, v5Rows);
                }

                model.close();
            }
        }

        // Write CSVs
        writeCsv(resultsCsv, resultRows);
        writeCsv(v5Csv, v5Rows);

        // Print summary
        printSummary(resultRows);

        System.out.println("\nResults written to:");
        System.out.println("  " + resultsCsv);
        System.out.println("  " + v5Csv);
    }

    /**
     * Execute DIVJOIN query through the full engine path.
     */
    private static int executeSimJoinQuery(Model model, String queryString) {
        Query query = QueryFactory.create(queryString);
        DatasetGraph dsg = DatasetGraphFactory.wrap(model.getGraph());
        Binding initialBinding = BindingFactory.binding();

        QueryEngineProbabilistic engine = new QueryEngineProbabilistic(
            query, dsg, initialBinding, ARQ.getContext().copy()
        );

        int count = 0;
        QueryIterator iter = engine.getPlan().iterator();
        try {
            while (iter.hasNext()) {
                iter.next();
                count++;
            }
        } finally {
            iter.close();
        }
        return count;
    }

    /**
     * Collect V5 AdaptiveSampler breakdown for a dataset.
     * Run one extra iteration and read the static stats.
     */
    private static void collectV5Stats(Model model, String queryString,
                                        String dataset, List<String[]> v5Rows) {
        // We need a fresh AdaptiveSampler to get stats.
        // The JSDivergence instances are created per-call inside QueryIterSimilarityJoin,
        // each with their own AdaptiveSampler. So we can't easily read their stats.
        //
        // Workaround: use a shared static AdaptiveSampler and read stats after.
        // For now, run direct GMM-level computation to collect stats.

        // Create a fresh adaptive sampler with the same params
        AdaptiveSampler sampler = new AdaptiveSampler(
            0.1, JSDivergenceConfig.SPRT_ALPHA,
            JSDivergenceConfig.SPRT_BETA, JSDivergenceConfig.SPRT_EPSILON
        );
        sampler.resetStats();

        // Load all GMM pairs from the model and run through adaptive sampler
        org.apache.jena.rdf.model.StmtIterator leftIter = model.listStatements(
            null,
            model.createProperty("http://example.org/prob#", "hasGMM"),
            (org.apache.jena.rdf.model.RDFNode) null
        );

        // Separate left and right entities
        List<org.apache.jena.probsparql.datatypes.GMMValue> leftGMMs = new ArrayList<>();
        List<org.apache.jena.probsparql.datatypes.GMMValue> rightGMMs = new ArrayList<>();

        while (leftIter.hasNext()) {
            org.apache.jena.rdf.model.Statement stmt = leftIter.nextStatement();
            String subjectUri = stmt.getSubject().getURI();
            String lexical = stmt.getObject().asLiteral().getLexicalForm();

            org.apache.jena.probsparql.datatypes.GMMValue gmm =
                (org.apache.jena.probsparql.datatypes.GMMValue)
                org.apache.jena.probsparql.datatypes.GMMDatatype.INSTANCE.parse(lexical);

            if (subjectUri.contains("left_")) {
                leftGMMs.add(gmm);
            } else if (subjectUri.contains("right_")) {
                rightGMMs.add(gmm);
            }
        }

        // Run nested loop through adaptive sampler
        for (org.apache.jena.probsparql.datatypes.GMMValue lg : leftGMMs) {
            for (org.apache.jena.probsparql.datatypes.GMMValue rg : rightGMMs) {
                if (lg.getDimensions() == rg.getDimensions()) {
                    sampler.computeJSDAdaptive(lg, rg, JSDivergenceConfig.V5_ADAPTIVE_MAX_SAMPLES);
                }
            }
        }

        v5Rows.add(new String[]{
            dataset,
            String.valueOf(sampler.getFilteredByBounds()),
            String.valueOf(sampler.getEarlyBySPRT()),
            String.valueOf(sampler.getFullStratified()),
            String.format("%.2f", sampler.getBoundsFilterRate() * 100),
            String.format("%.2f", sampler.getSPRTEarlyRate() * 100),
            String.format("%.2f", sampler.getFullSamplingRate() * 100)
        });

        System.out.printf("    V5 breakdown: Bounds=%d (%.1f%%), SPRT=%d (%.1f%%), Full=%d (%.1f%%)%n",
            sampler.getFilteredByBounds(), sampler.getBoundsFilterRate() * 100,
            sampler.getEarlyBySPRT(), sampler.getSPRTEarlyRate() * 100,
            sampler.getFullStratified(), sampler.getFullSamplingRate() * 100);
    }

    // -----------------------------------------------------------------------
    // Utilities
    // -----------------------------------------------------------------------

    private static String loadFile(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                w.println(String.join(",", row));
            }
        }
        System.out.println("  Wrote " + path + " (" + (rows.size() - 1) + " data rows)");
    }

    private static void printSummary(List<String[]> rows) {
        System.out.println("\n=== SUMMARY (average ms per dataset) ===");
        System.out.printf("%-15s | %10s | %10s | %10s | %10s%n",
            "Method", "Easy", "Medium", "Hard", "Mixed");
        System.out.println("-".repeat(65));

        // Group by method+dataset, compute averages
        Map<String, Map<String, List<Double>>> grouped = new LinkedHashMap<>();
        for (int i = 1; i < rows.size(); i++) {
            String method  = rows.get(i)[0];
            String dataset = rows.get(i)[1];
            double time    = Double.parseDouble(rows.get(i)[3]);
            grouped.computeIfAbsent(method, k -> new LinkedHashMap<>())
                   .computeIfAbsent(dataset, k -> new ArrayList<>())
                   .add(time);
        }

        for (Map.Entry<String, Map<String, List<Double>>> me : grouped.entrySet()) {
            String method = me.getKey();
            Map<String, List<Double>> byDataset = me.getValue();
            System.out.printf("%-15s |", method);
            for (String ds : DATASETS) {
                List<Double> times = byDataset.getOrDefault(ds, List.of(0.0));
                double avg = times.stream().mapToDouble(d -> d).average().orElse(0);
                System.out.printf(" %8.1f ms |", avg);
            }
            System.out.println();
        }
    }
}
