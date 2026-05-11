package org.apache.jena.probsparql;

import org.apache.jena.probsparql.exp2.PruningStats;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Experiment 2 — In-engine filtering vs DIVJOIN over remote Fuseki endpoints.
 *
 * <p>The benchmark client runs locally and sends SPARQL queries to a preloaded
 * Fuseki service for each configuration. DIVJOIN pruning statistics are exposed
 * by the server through {@code prob:lastDivJoinStats(...)} after an instrumented
 * DIVJOIN run.</p>
 */
public class Exp2Benchmark {

    private static int WARMUP_RUNS = 3;
    private static int BENCHMARK_RUNS = 10;

    private static final int[] DEFAULT_N_PAIRS = {5_000};
    private static final double[] UNIMODAL_FRACS = {0.2, 0.5, 0.8};
    private static final String[] SEL_LABELS = {"10pct", "50pct", "90pct"};
    private static final double[] SEL_PERCENTILES = {0.10, 0.50, 0.90};

    private static final String THETA_PLACEHOLDER = "__THETA__";

    private static final String STATS_QUERY = """
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?totalPairs ?prunedDim ?prunedMean ?prunedVar ?prunedBounds ?fullJSD ?resultCount ?pruningRate WHERE {
          BIND(prob:lastDivJoinStats("totalPairs") AS ?totalPairs)
          BIND(prob:lastDivJoinStats("prunedDim") AS ?prunedDim)
          BIND(prob:lastDivJoinStats("prunedMean") AS ?prunedMean)
          BIND(prob:lastDivJoinStats("prunedVar") AS ?prunedVar)
          BIND(prob:lastDivJoinStats("prunedBounds") AS ?prunedBounds)
          BIND(prob:lastDivJoinStats("fullJSD") AS ?fullJSD)
          BIND(prob:lastDivJoinStats("resultCount") AS ?resultCount)
          BIND(prob:lastDivJoinStats("pruningRate") AS ?pruningRate)
        }""";

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp2";
        String queryDir = "benchmark/queries/exp2";
        String endpointTemplate = null;
        int[] nPairsList = DEFAULT_N_PAIRS;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--query-dir" -> queryDir = args[++i];
                case "--warmup" -> WARMUP_RUNS = Integer.parseInt(args[++i]);
                case "--runs" -> BENCHMARK_RUNS = Integer.parseInt(args[++i]);
                case "--npairs" -> nPairsList = parseIntList(args[++i]);
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp2Benchmark: Remote In-engine Filtering vs DIVJOIN ===");
        System.out.printf("Endpoint template=%s%n", endpointTemplate);
        System.out.printf("Warmup=%d  Runs=%d%n", WARMUP_RUNS, BENCHMARK_RUNS);
        System.out.printf("Query dir=%s%n%n", queryDir);

        String qInEngineCheapFirstTemplate = readQueryTemplate(queryDir, "inengine_cheapfirst.sparql");
        String qInEngineJsdFirstTemplate = readQueryTemplate(queryDir, "inengine_jsdfirst.sparql");
        String qCollectMultimodal = readQueryTemplate(queryDir, "collect_multimodal.sparql");
        String qDivJoinTemplate = readQueryTemplate(queryDir, "similarityjoin.sparql");
        List<String[]> calibRows = new ArrayList<>();
        List<String[]> inEngineCheapFirstRows = new ArrayList<>();
        List<String[]> inEngineJsdFirstRows = new ArrayList<>();
        List<String[]> divJoinRows = new ArrayList<>();
        List<String[]> pruningRows = new ArrayList<>();

        calibRows.add(new String[]{"NPairs", "UnimodalFrac", "TotalPairs", "MultimodalPairs",
                "Theta_10pct", "Theta_50pct", "Theta_90pct"});
        inEngineCheapFirstRows.add(new String[]{"NPairs", "UnimodalFrac", "Selectivity", "Theta",
                "Time_ms", "ResultCount"});
        inEngineJsdFirstRows.add(new String[]{"NPairs", "UnimodalFrac", "Selectivity", "Theta",
                "Time_ms", "ResultCount"});
        divJoinRows.add(new String[]{"NPairs", "UnimodalFrac", "Selectivity", "Theta",
                "Time_ms", "ResultCount"});
        pruningRows.add(new String[]{"NPairs", "UnimodalFrac", "Selectivity", "Theta",
                "TotalPairs", "PrunedDim", "PrunedMean", "PrunedVar", "PrunedBounds",
                "FullJSD", "ResultCount", "PruningRate"});

        for (int nPairs : nPairsList) {
            int n = (int) Math.ceil((1.0 + Math.sqrt(1.0 + 8.0 * nPairs)) / 2.0);

            for (double unimodalFrac : UNIMODAL_FRACS) {
                String datasetName = datasetName(nPairs, unimodalFrac);
                String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, datasetName);
                int actualPairs = n * (n - 1) / 2;
                int nMultimodal = n - (int) (n * unimodalFrac);
                int multimodalPairs = nMultimodal * (nMultimodal - 1) / 2;

                System.out.printf("==== dataset=%s  nPairs≈%d  n=%d  unimodalFrac=%.1f ====%n",
                        datasetName, nPairs, n, unimodalFrac);

                double[] thetas = calibrate(endpoint, qCollectMultimodal);
                System.out.printf("  Calib: theta10=%.4f theta50=%.4f theta90=%.4f%n",
                        thetas[0], thetas[1], thetas[2]);
                calibRows.add(new String[]{str(nPairs), fmt(unimodalFrac), str(actualPairs), str(multimodalPairs),
                        fmt(thetas[0]), fmt(thetas[1]), fmt(thetas[2])});

                for (int si = 0; si < SEL_LABELS.length; si++) {
                    String sel = SEL_LABELS[si];
                    double theta = thetas[si];
                    System.out.printf("  -- sel=%s theta=%.4f --%n", sel, theta);

                    TimingResult cheap = measureMedian(endpoint, withTheta(qInEngineCheapFirstTemplate, theta));
                    System.out.printf("     InEngine_CheapFirst : %.2f ms (%d results)%n",
                            cheap.timeMs, cheap.resultCount);
                    inEngineCheapFirstRows.add(new String[]{str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            fmt(cheap.timeMs), str(cheap.resultCount)});

                    TimingResult jsdFirst = measureMedian(endpoint, withTheta(qInEngineJsdFirstTemplate, theta));
                    System.out.printf("     InEngine_JSDFirst   : %.2f ms (%d results)%n",
                            jsdFirst.timeMs, jsdFirst.resultCount);
                    inEngineJsdFirstRows.add(new String[]{str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            fmt(jsdFirst.timeMs), str(jsdFirst.resultCount)});

                    String divJoinQuery = withTheta(qDivJoinTemplate, theta);
                    TimingResult divJoin = measureMedian(endpoint, divJoinQuery);
                    PruningStats stats = collectRemotePruningStats(endpoint, divJoinQuery);
                    divJoin.pruning = stats;
                    System.out.printf("     DIVJOIN pruning=%.1f%% : %.2f ms (%d results)%n",
                            stats.pruningRate() * 100.0, divJoin.timeMs, divJoin.resultCount);
                    divJoinRows.add(new String[]{str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            fmt(divJoin.timeMs), str(divJoin.resultCount)});
                    pruningRows.add(new String[]{str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            str(stats.totalPairs), str(stats.prunedByDim), str(stats.prunedByMean),
                            str(stats.prunedByVariance), str(stats.prunedByBounds), str(stats.computedFullJSD),
                            str(stats.resultCount), fmt(stats.pruningRate())});

                    checkResultConsistency(cheap, jsdFirst, divJoin, nPairs, unimodalFrac, sel);
                }
                System.out.println();
            }
        }

        writeCsv(outputDir + "/exp2_calibration.csv", calibRows);
        writeCsv(outputDir + "/exp2_inengine_cheapfirst.csv", inEngineCheapFirstRows);
        writeCsv(outputDir + "/exp2_inengine_jsdfirst.csv", inEngineJsdFirstRows);
        writeCsv(outputDir + "/exp2_similarityjoin.csv", divJoinRows);
        writeCsv(outputDir + "/exp2_pruning_stats.csv", pruningRows);
        System.out.println("Results written to: " + outputDir);
    }

    private static double[] calibrate(String endpoint, String sparql) {
        List<Double> jsdValues = new ArrayList<>();
        RemoteBenchmarkClient.forEachSolution(endpoint, sparql, row -> {
            if (row.contains("jsd")) {
                jsdValues.add(row.getLiteral("jsd").getDouble());
            }
        });
        if (jsdValues.isEmpty()) {
            return new double[]{0.1, 0.3, 0.5};
        }
        Collections.sort(jsdValues);
        double[] thetas = new double[SEL_PERCENTILES.length];
        for (int i = 0; i < SEL_PERCENTILES.length; i++) {
            thetas[i] = percentile(jsdValues, SEL_PERCENTILES[i]);
        }
        return thetas;
    }

    private static TimingResult measureMedian(String endpoint, String sparql) {
        for (int i = 0; i < WARMUP_RUNS; i++) {
            RemoteBenchmarkClient.execCount(endpoint, sparql);
        }
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = RemoteBenchmarkClient.execCount(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return new TimingResult(times[times.length / 2] / 1_000_000.0, cnt);
    }

    private static PruningStats collectRemotePruningStats(String endpoint, String divJoinQuery) {
        RemoteBenchmarkClient.execCount(endpoint, divJoinQuery);
        PruningStats stats = new PruningStats();
        RemoteBenchmarkClient.forEachSolution(endpoint, STATS_QUERY, row -> {
            stats.totalPairs = row.getLiteral("totalPairs").getLong();
            stats.prunedByDim = row.getLiteral("prunedDim").getLong();
            stats.prunedByMean = row.getLiteral("prunedMean").getLong();
            stats.prunedByVariance = row.getLiteral("prunedVar").getLong();
            stats.prunedByBounds = row.getLiteral("prunedBounds").getLong();
            stats.computedFullJSD = row.getLiteral("fullJSD").getLong();
            stats.resultCount = row.getLiteral("resultCount").getLong();
        });
        return stats;
    }

    private static void checkResultConsistency(TimingResult cheap, TimingResult jsdFirst, TimingResult divJoin,
                                               int nPairs, double unimodalFrac, String sel) {
        if (!(cheap.resultCount == jsdFirst.resultCount && cheap.resultCount == divJoin.resultCount)) {
            System.out.printf(
                    "  [WARN] Result mismatch at nPairs=%d uf=%.1f sel=%s :: InEngine_CF=%d InEngine_JF=%d DIVJOIN=%d%n",
                    nPairs, unimodalFrac, sel, cheap.resultCount, jsdFirst.resultCount, divJoin.resultCount);
        }
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return 0.3;
        int idx = Math.min((int) Math.ceil(p * n) - 1, n - 1);
        return sorted.get(Math.max(0, idx));
    }

    private static String readQueryTemplate(String queryDir, String fileName) {
        File file = new File(queryDir, fileName);
        if (!file.exists()) {
            throw new IllegalArgumentException("Missing query file: " + file);
        }
        try {
            return Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read query file: " + file, e);
        }
    }

    private static String withTheta(String template, double theta) {
        return template.replace(THETA_PLACEHOLDER, fmt(theta));
    }

    private static String datasetName(int nPairs, double unimodalFrac) {
        String ufLabel = String.format(Locale.ROOT, "%.1f", unimodalFrac).replace('.', 'p');
        return "exp2_npairs_" + nPairs + "_uf_" + ufLabel;
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
        System.out.println("Wrote: " + path);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "NaN" : String.format(Locale.ROOT, "%.6f", v);
    }

    private static String str(long v) {
        return String.valueOf(v);
    }

    private static int[] parseIntList(String spec) {
        String[] parts = spec.split(",");
        int[] vals = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vals[i] = Integer.parseInt(parts[i].trim());
        }
        return vals;
    }

    static class TimingResult {
        double timeMs;
        int resultCount;
        PruningStats pruning = null;

        TimingResult(double timeMs, int resultCount) {
            this.timeMs = timeMs;
            this.resultCount = resultCount;
        }
    }
}
