package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exp 4.4 — End-to-end query performance over remote Fuseki endpoints.
 *
 * <p>Runs Exp1-aligned Q2/Q4 queries on preloaded GMM and histogram services:
 * {@code exp1_{scale}_K3}, {@code exp4_{scale}_hist_B50}, and
 * {@code exp4_{scale}_hist_B100}.</p>
 */
public class Exp4EndToEnd {

    private static int WARMUP_RUNS = 3;
    private static int TIMED_RUNS = 10;
    private static boolean DEMO_MODE = false;
    private static final String QUERY_DIR = "benchmark/queries/exp4";

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4";
        String endpointTemplate = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--demo" -> DEMO_MODE = true;
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }
        if (DEMO_MODE) {
            WARMUP_RUNS = 1;
            TIMED_RUNS = 1;
            System.out.println("  [DEMO MODE: scale=E3, warmup=1, runs=1]");
        }

        new File(outputDir).mkdirs();
        String q2Filter = readQuery(QUERY_DIR + "/q2_filter.sparql");
        String q4Jsd = readQuery(QUERY_DIR + "/q4_jsd.sparql");
        QueryFactory.create(q2Filter);
        QueryFactory.create(q4Jsd);

        System.out.println("=== Exp 4.4: Remote End-to-End Query Performance ===");
        System.out.printf("Endpoint template=%s  warmup=%d  runs=%d%n%n",
                endpointTemplate, WARMUP_RUNS, TIMED_RUNS);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Query", "Scale", "DistType", "Param", "N_entities",
                "MedianMs", "IQRMs"});

        for (String scale : DEMO_MODE ? new String[]{"E3"} : new String[]{"E3", "E5", "E7"}) {
            System.out.println("-- Scale " + scale + " --------------------------------------");

            runDataset(endpointTemplate, "exp1_" + scale + "_K3",
                    "Q2-Filter", scale, "GMM", "K=3", q2Filter, rows);
            runDataset(endpointTemplate, "exp1_" + scale + "_K3",
                    "Q4-JSD", scale, "GMM", "K=3", q4Jsd, rows);

            for (int b : new int[]{50, 100}) {
                String dataset = "exp4_" + scale + "_hist_B" + b;
                runDataset(endpointTemplate, dataset,
                        "Q2-Filter", scale, "Hist", "B=" + b, q2Filter, rows);
                runDataset(endpointTemplate, dataset,
                        "Q4-JSD", scale, "Hist", "B=" + b, q4Jsd, rows);
            }
        }

        writeCsv(outputDir + "/exp4_endtoend.csv", rows);
        System.out.println("Results -> " + outputDir + "/exp4_endtoend.csv");
    }

    private static void runDataset(String endpointTemplate, String dataset, String qid,
                                   String scale, String type, String param,
                                   String sparql, List<String[]> rows) {
        String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, dataset);
        int entityCount = countEntities(endpoint);
        for (int i = 0; i < WARMUP_RUNS; i++) {
            RemoteBenchmarkClient.execCount(endpoint, sparql);
        }

        long[] times = new long[TIMED_RUNS];
        int resultCount = 0;
        for (int i = 0; i < TIMED_RUNS; i++) {
            long t0 = System.nanoTime();
            resultCount = RemoteBenchmarkClient.execCount(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double medMs = times[TIMED_RUNS / 2] / 1_000_000.0;
        double iqrMs = (times[TIMED_RUNS * 3 / 4] - times[TIMED_RUNS / 4]) / 1_000_000.0;

        System.out.printf("  %-9s %-5s %-5s dataset=%s median=%.1f ms iqr=%.1f ms rows=%d%n",
                qid, type, param, dataset, medMs, iqrMs, resultCount);
        rows.add(new String[]{qid, scale, type, param, String.valueOf(entityCount),
                fmt(medMs), fmt(iqrMs)});
    }

    private static int countEntities(String endpoint) {
        String sparql = """
            SELECT (COUNT(DISTINCT ?gear) AS ?n) WHERE {
              ?gear a <http://example.org/ontology/anglegrinder#CrownGear> .
            }""";
        final int[] count = {-1};
        RemoteBenchmarkClient.forEachSolution(endpoint, sparql, sol -> {
            if (sol.contains("n")) {
                count[0] = sol.getLiteral("n").getInt();
            }
        });
        return count[0];
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String readQuery(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }
}
