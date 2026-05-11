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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exp1 sub-experiment: representation invariance under GMM component permutation.
 *
 * <p>Compares the same Exp1 queries on:
 * <ul>
 *   <li>original probabilistic datasets</li>
 *   <li>permuted probabilistic datasets (same GMM semantics, reordered components)</li>
 * </ul>
 * while keeping the deterministic baseline unchanged.</p>
 *
 * <p>The benchmark runs both probabilistic conditions via remote Fuseki query
 * endpoints and alternates execution order per iteration to reduce run-to-run
 * drift.</p>
 */
public class Exp1PermutationBenchmark {

    private static int WARMUP_RUNS = 3;
    private static int BENCHMARK_RUNS = 10;

    private static final String DEFAULT_SCALE = "E5";
    private static final int[] DEFAULT_K_VALUES = {3, 5, 10};
    private static final String DEFAULT_Q4_VARIANT = "poly";

    private static final String[] DET_QUERY_IDS = {"Q1", "Q2", "Q3"};
    private static final String[] PROB_QUERY_IDS = {"Q1", "Q2", "Q3", "Q4"};

    private static final Map<String, String> QUERY_LABELS = new LinkedHashMap<>();
    static {
        QUERY_LABELS.put("Q1", "Retrieval");
        QUERY_LABELS.put("Q2", "Probabilistic Filtering");
        QUERY_LABELS.put("Q3", "Uncertainty Propagation");
        QUERY_LABELS.put("Q4", "Distribution Comparison");
    }

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String queryDir = "benchmark/queries/exp1/permutation";
        String outputDir = "benchmark/results/exp1/permutation";
        String endpointTemplate = null;
        String scale = DEFAULT_SCALE;
        String q4Variant = DEFAULT_Q4_VARIANT;
        List<Integer> kValues = new ArrayList<>();
        for (int k : DEFAULT_K_VALUES) kValues.add(k);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--query-dir" -> queryDir = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--scale" -> scale = args[++i];
                case "--q4-variant" -> q4Variant = args[++i].trim().toLowerCase();
                case "--k-values" -> {
                    kValues.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        kValues.add(Integer.parseInt(args[++i]));
                    }
                }
                case "--warmup" -> WARMUP_RUNS = Integer.parseInt(args[++i]);
                case "--runs" -> BENCHMARK_RUNS = Integer.parseInt(args[++i]);
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp1: GMM Permutation Invariance ===");
        System.out.printf("Endpoint template : %s%n", endpointTemplate);
        System.out.printf("Query dir         : %s%n", queryDir);
        System.out.printf("Scale             : %s%n", scale);
        System.out.printf("K values          : %s%n", kValues);
        System.out.printf("Q4 variant        : %s%n", q4Variant);
        System.out.printf("Warmup            : %d   Benchmark: %d runs%n", WARMUP_RUNS, BENCHMARK_RUNS);
        System.out.println();

        List<String[]> rawRows = new ArrayList<>();
        List<String[]> sumRows = new ArrayList<>();

        rawRows.add(new String[]{"Scale", "K", "QueryID", "QueryType", "Condition", "Run", "Time_ms", "ResultCount"});
        sumRows.add(new String[]{"Scale", "K", "QueryID", "QueryType", "DET_ms", "DET_iqr_ms",
                "Original_ms", "Original_iqr_ms", "Permuted_ms", "Permuted_iqr_ms",
                "PermutedOverOriginal", "OriginalCount", "PermutedCount", "CountsMatch"});

        String detDataset = "exp1_" + scale + "_det";
        String detEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, detDataset);

        Map<String, TimingAndCount> detResults = new LinkedHashMap<>();
        for (String qid : DET_QUERY_IDS) {
            String q = loadQuery(queryDir + "/det/" + qid.toLowerCase() + ".sparql");
            TimingAndCount result = runSingleCondition(detEndpoint, q);
            detResults.put(qid, result);
            for (int r = 0; r < BENCHMARK_RUNS; r++) {
                rawRows.add(row(scale, "DET", qid, QUERY_LABELS.get(qid), "DET",
                        r + 1, result.times[r] / 1e6, result.resultCount));
            }
            System.out.printf("  %-2s DET        median=%8.3f ms%n", qid, medianMs(result.times));
        }
        System.out.println();

        for (int k : kValues) {
            String origDataset = "exp1_" + scale + "_K" + k;
            String permDataset = "exp1_" + scale + "_K" + k + "_permuted";
            String origEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, origDataset);
            String permEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, permDataset);

            System.out.printf("── K=%d ───────────────────────────────────────────%n", k);
            System.out.printf("  original endpoint: %s%n", origEndpoint);
            System.out.printf("  permuted endpoint: %s%n", permEndpoint);

            for (String qid : PROB_QUERY_IDS) {
                String qPath = queryDir + "/prob/" + qid.toLowerCase() + ".sparql";
                if ("Q4".equals(qid)) {
                    qPath = switch (q4Variant) {
                        case "legacy", "jsdivergence" -> queryDir + "/prob/q4.sparql";
                        case "poly", "jsd" -> queryDir + "/prob/q4_jsd.sparql";
                        default -> throw new IllegalArgumentException(
                                "Unknown --q4-variant: " + q4Variant + " (expected legacy or poly)");
                    };
                }
                String q = loadQuery(qPath);
                PairedTiming paired = runPairedConditions(origEndpoint, permEndpoint, q);

                double origMed = medianMs(paired.original.times);
                double permMed = medianMs(paired.permuted.times);
                String ratio = fmt4(permMed / origMed);
                boolean countsMatch = paired.original.resultCount == paired.permuted.resultCount;

                for (int r = 0; r < BENCHMARK_RUNS; r++) {
                    rawRows.add(row(scale, String.valueOf(k), qid, QUERY_LABELS.get(qid), "ORIGINAL",
                            r + 1, paired.original.times[r] / 1e6, paired.original.resultCount));
                    rawRows.add(row(scale, String.valueOf(k), qid, QUERY_LABELS.get(qid), "PERMUTED",
                            r + 1, paired.permuted.times[r] / 1e6, paired.permuted.resultCount));
                }

                TimingAndCount det = detResults.get(qid);
                String detMed = det == null ? "—" : fmt4(medianMs(det.times));
                String detIqr = det == null ? "—" : fmt4(iqrMs(det.times));

                sumRows.add(new String[]{
                        scale,
                        String.valueOf(k),
                        qid,
                        QUERY_LABELS.get(qid),
                        detMed,
                        detIqr,
                        fmt4(origMed),
                        fmt4(iqrMs(paired.original.times)),
                        fmt4(permMed),
                        fmt4(iqrMs(paired.permuted.times)),
                        ratio,
                        String.valueOf(paired.original.resultCount),
                        String.valueOf(paired.permuted.resultCount),
                        countsMatch ? "Yes" : "No"
                });

                System.out.printf("  %-2s  orig=%8.3f ms  perm=%8.3f ms  ratio=%s  counts=%s%n",
                        qid, origMed, permMed, ratio, countsMatch ? "match" : "DIFF");
            }
            System.out.println();
        }

        writeCsv(outputDir + "/exp1_permutation_raw.csv", rawRows);
        writeCsv(outputDir + "/exp1_permutation_summary.csv", sumRows);
        System.out.printf("Results written to:%n  %s%n  %s%n",
                outputDir + "/exp1_permutation_raw.csv",
                outputDir + "/exp1_permutation_summary.csv");
    }

    private static String loadQuery(String path) throws IOException {
        String sparql = Files.readString(Path.of(path));
        QueryFactory.create(sparql);
        return sparql;
    }

    private static TimingAndCount runSingleCondition(String endpoint, String sparql) {
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(endpoint, sparql);

        long[] times = new long[BENCHMARK_RUNS];
        int resultCount = -1;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            int count = execCount(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
            if (resultCount < 0) resultCount = count;
        }
        return new TimingAndCount(times, resultCount);
    }

    private static PairedTiming runPairedConditions(String originalEndpoint, String permutedEndpoint, String sparql) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            if ((i % 2) == 0) {
                execCount(originalEndpoint, sparql);
                execCount(permutedEndpoint, sparql);
            } else {
                execCount(permutedEndpoint, sparql);
                execCount(originalEndpoint, sparql);
            }
        }

        long[] originalTimes = new long[BENCHMARK_RUNS];
        long[] permutedTimes = new long[BENCHMARK_RUNS];
        int originalCount = -1;
        int permutedCount = -1;

        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            if ((i % 2) == 0) {
                long t0 = System.nanoTime();
                int c0 = execCount(originalEndpoint, sparql);
                originalTimes[i] = System.nanoTime() - t0;
                if (originalCount < 0) originalCount = c0;

                long t1 = System.nanoTime();
                int c1 = execCount(permutedEndpoint, sparql);
                permutedTimes[i] = System.nanoTime() - t1;
                if (permutedCount < 0) permutedCount = c1;
            } else {
                long t1 = System.nanoTime();
                int c1 = execCount(permutedEndpoint, sparql);
                permutedTimes[i] = System.nanoTime() - t1;
                if (permutedCount < 0) permutedCount = c1;

                long t0 = System.nanoTime();
                int c0 = execCount(originalEndpoint, sparql);
                originalTimes[i] = System.nanoTime() - t0;
                if (originalCount < 0) originalCount = c0;
            }
        }

        return new PairedTiming(
                new TimingAndCount(originalTimes, originalCount),
                new TimingAndCount(permutedTimes, permutedCount)
        );
    }

    private static int execCount(String endpoint, String sparql) {
        return RemoteBenchmarkClient.execCount(endpoint, sparql);
    }

    private static double medianMs(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        return s[s.length / 2] / 1_000_000.0;
    }

    private static double iqrMs(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        double q1 = s[Math.max(0, s.length / 4)] / 1_000_000.0;
        double q3 = s[Math.min(s.length - 1, 3 * s.length / 4)] / 1_000_000.0;
        return q3 - q1;
    }

    private static String[] row(String scale, String k, String qid, String qtype,
                                String condition, int run, double timeMs, int resultCount) {
        return new String[]{
                scale, k, qid, qtype, condition, String.valueOf(run), fmt4(timeMs), String.valueOf(resultCount)
        };
    }

    private static String fmt4(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.4f", v);
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
    }

    private record TimingAndCount(long[] times, int resultCount) {}
    private record PairedTiming(TimingAndCount original, TimingAndCount permuted) {}
}
