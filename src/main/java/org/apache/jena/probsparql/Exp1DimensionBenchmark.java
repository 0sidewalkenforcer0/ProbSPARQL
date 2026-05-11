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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exp1 supplement: fixed scale E5, fixed K=3, varying GMM dimension d in
 * {1, 2, 4, 8}.
 * Runs Q1-Q3 and two Q4 probabilistic variants against remote Fuseki query
 * endpoints. Endpoint names are derived from the dataset ids
 * exp1_{scale}_K{k}_D{d}.
 */
public class Exp1DimensionBenchmark {

    private static int WARMUP_RUNS = 3;
    private static int BENCHMARK_RUNS = 10;

    private static final String DEFAULT_SCALE = "E5";
    private static final int DEFAULT_K = 3;
    private static final int[] DEFAULT_DIMENSIONS = {1, 2, 4, 8};

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String queryDir = "benchmark/queries/exp1/dimension";
        String outputDir = "benchmark/results/exp1/dimension";
        String endpointTemplate = null;
        String scale = DEFAULT_SCALE;
        int k = DEFAULT_K;
        List<Integer> dimensions = new ArrayList<>();
        for (int d : DEFAULT_DIMENSIONS) dimensions.add(d);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--query-dir" -> queryDir = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--scale" -> scale = args[++i];
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--dimensions" -> {
                    dimensions.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        dimensions.add(Integer.parseInt(args[++i]));
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
        List<String[]> rawRows = new ArrayList<>();
        List<String[]> sumRows = new ArrayList<>();

        System.out.println("=== Exp1 Dimension Scaling (Q1-Q4) ===");
        System.out.printf("Scale      : %s%n", scale);
        System.out.printf("K          : %d%n", k);
        System.out.printf("Dimensions : %s%n", dimensions);
        System.out.printf("Warmup     : %d%n", WARMUP_RUNS);
        System.out.printf("Runs       : %d%n", BENCHMARK_RUNS);
        System.out.printf("Endpoint   : %s%n", endpointTemplate);
        System.out.printf("Query dir  : %s%n", queryDir);
        System.out.printf("Output dir : %s%n", outputDir);
        System.out.println();

        for (int d : dimensions) {
            String dataset = "exp1_" + scale + "_K" + k + "_D" + d;
            String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, dataset);
            System.out.printf("D=%d endpoint: %s%n", d, endpoint);

            Map<String, Double> detMedians = new HashMap<>();

            for (String qid : List.of("Q1", "Q2", "Q3")) {
                String detQuery = loadQuery(queryDir + "/det/" + qid.toLowerCase() + ".sparql");
                RunResult det = runTimed(endpoint, detQuery);
                detMedians.put(qid, median(det.timesNs));
                addRows(rawRows, sumRows, scale, k, d, qid, "DET", "STD", det, "—");
            }

            String q1Prob = loadQuery(queryDir + "/prob/q1.sparql");
            String q2Prob = loadQueryWithReplacement(
                    queryDir + "/prob/q2.sparql",
                    "__CDF_POINT__",
                    cdfPointLiteral(d)
            );
            String q3Prob = loadQuery(queryDir + "/prob/q3.sparql");
            String q4Legacy = loadQuery(queryDir + "/prob/q4.sparql");
            String q4Jsd = loadQuery(queryDir + "/prob/q4_jsd.sparql");

            addProbRows(rawRows, sumRows, scale, k, d, "Q1", "STD", runTimed(endpoint, q1Prob), detMedians.get("Q1"));
            addProbRows(rawRows, sumRows, scale, k, d, "Q2", "STD", runTimed(endpoint, q2Prob), detMedians.get("Q2"));
            addProbRows(rawRows, sumRows, scale, k, d, "Q3", "STD", runTimed(endpoint, q3Prob), detMedians.get("Q3"));
            addRows(rawRows, sumRows, scale, k, d, "Q4", "PROB", "JSDIVERGENCE", runTimed(endpoint, q4Legacy), "—");
            addRows(rawRows, sumRows, scale, k, d, "Q4", "PROB", "JSD", runTimed(endpoint, q4Jsd), "—");
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/exp1_dimension_raw.csv"))) {
            pw.println("Scale,K,Dimension,QueryID,Type,Variant,Run,Time_ms,ResultCount");
            for (String[] row : rawRows) pw.println(String.join(",", row));
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/exp1_dimension_summary.csv"))) {
            pw.println("Scale,K,Dimension,QueryID,Type,Variant,Median_ms,IQR_ms,ResultCount,OverheadRatio");
            for (String[] row : sumRows) pw.println(String.join(",", row));
        }
    }

    private static void addProbRows(
            List<String[]> rawRows,
            List<String[]> sumRows,
            String scale,
            int k,
            int d,
            String qid,
            String variant,
            RunResult result,
            double detMedian
    ) {
        String ratio = detMedian > 0 ? fmt4(median(result.timesNs) / detMedian) : "NaN";
        addRows(rawRows, sumRows, scale, k, d, qid, "PROB", variant, result, ratio);
    }

    private static void addRows(
            List<String[]> rawRows,
            List<String[]> sumRows,
            String scale,
            int k,
            int d,
            String qid,
            String type,
            String variant,
            RunResult result,
            String ratio
    ) {
        double medMs = median(result.timesNs);
        double iqrMs = iqr(result.timesNs);
        for (int r = 0; r < BENCHMARK_RUNS; r++) {
            rawRows.add(new String[]{
                    scale,
                    String.valueOf(k),
                    String.valueOf(d),
                    qid,
                    type,
                    variant,
                    String.valueOf(r + 1),
                    fmt4(result.timesNs[r] / 1e6),
                    String.valueOf(result.resultCount)
            });
        }

        sumRows.add(new String[]{
                scale,
                String.valueOf(k),
                String.valueOf(d),
                qid,
                type,
                variant,
                fmt4(medMs),
                fmt4(iqrMs),
                String.valueOf(result.resultCount),
                ratio
        });
    }

    private static String loadQuery(String path) throws IOException {
        String sparql = Files.readString(Path.of(path));
        QueryFactory.create(sparql);
        return sparql;
    }

    private static String loadQueryWithReplacement(String path, String placeholder, String replacement) throws IOException {
        String sparql = Files.readString(Path.of(path));
        String replaced = sparql.replace(placeholder, replacement);
        QueryFactory.create(replaced);
        return replaced;
    }

    private static String cdfPointLiteral(int d) {
        StringBuilder sb = new StringBuilder("\"[");
        for (int i = 0; i < d; i++) {
            if (i > 0) sb.append(",");
            double v = (i == 0) ? 9.8 : 0.25 * i + 0.075;
            sb.append(String.format(Locale.ROOT, "%.3f", v));
        }
        sb.append("]\"");
        return sb.toString();
    }

    private static RunResult runTimed(String endpoint, String sparql) {
        for (int i = 0; i < WARMUP_RUNS; i++) drain(endpoint, sparql);
        long[] times = new long[BENCHMARK_RUNS];
        int count = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            count = drain(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
        }
        return new RunResult(times, count);
    }

    private static int drain(String endpoint, String sparql) {
        return RemoteBenchmarkClient.execCount(endpoint, sparql);
    }

    private static double median(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        return s[s.length / 2] / 1_000_000.0;
    }

    private static double iqr(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        double q1 = s[Math.max(0, s.length / 4)] / 1_000_000.0;
        double q3 = s[Math.min(s.length - 1, 3 * s.length / 4)] / 1_000_000.0;
        return q3 - q1;
    }

    private static String fmt4(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.4f", v);
    }

    private record RunResult(long[] timesNs, int resultCount) {}
}
