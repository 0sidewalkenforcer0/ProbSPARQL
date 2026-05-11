package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exp 4.2 — Remote per-type operation benchmark.
 *
 * <p>This runner measures remote Fuseki query execution latency, including
 * endpoint execution and HTTP result transfer. The datasets must be preloaded
 * on the server with the fixed service names used below.</p>
 */
public class Exp4MicroBenchmark {

    private static int N = 1_000;
    private static int WARMUP_RUNS = 3;
    private static int TIMED_RUNS = 10;
    private static boolean DEMO_MODE = false;

    private static final String Q_MEAN = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE { ?e uq:hasDistribution ?d . BIND(prob:mean(?d) AS ?v) }""";

    private static final String Q_STD = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE { ?e uq:hasDistribution ?d . BIND(prob:std(?d) AS ?v) }""";

    private static final String Q_MAP = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE { ?e uq:hasDistribution ?d . BIND(prob:map(?d) AS ?v) }""";

    private static final String Q_CDF = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE { ?e uq:hasDistribution ?d . BIND(prob:cdf(?d, 15.0) AS ?v) }""";

    private static final String Q_CDF_DIR = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE { ?e uq:hasDistribution ?d . BIND(prob:cdf(?d, 0.3) AS ?v) }""";

    private static final String Q_JSD = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?v WHERE {
            ?e1 uq:hasDistribution ?d1 . ?e2 uq:hasDistribution ?d2 .
            FILTER(str(?e1) < str(?e2))
            BIND(prob:jsd(?d1, ?d2) AS ?v)
        } LIMIT 200""";

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
            System.out.println("  [DEMO MODE: warmup=1, runs=1; remote dataset size unchanged]");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.2: Remote Per-Type Operation Benchmark ===");
        System.out.printf("Endpoint template=%s  N=%d  warmup=%d  runs=%d%n%n",
                endpointTemplate, N, WARMUP_RUNS, TIMED_RUNS);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Function", "DistType", "Param", "N", "MedianUs", "IQRUs"});

        String gmm = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_micro_gmm_K3");
        benchmark("prob:mean", "GMM", "K=3", N, gmm, Q_MEAN, rows);
        benchmark("prob:std", "GMM", "K=3", N, gmm, Q_STD, rows);
        benchmark("prob:map", "GMM", "K=3", N, gmm, Q_MAP, rows);
        benchmark("prob:cdf", "GMM", "K=3", N, gmm, Q_CDF, rows);
        benchmark("prob:jsd", "GMM", "K=3", N, gmm, Q_JSD, rows);

        for (int b : DEMO_MODE ? new int[]{50} : new int[]{20, 50, 100}) {
            String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_micro_hist_B" + b);
            String p = "B=" + b;
            benchmark("prob:mean", "Hist", p, N, endpoint, Q_MEAN, rows);
            benchmark("prob:std", "Hist", p, N, endpoint, Q_STD, rows);
            benchmark("prob:map", "Hist", p, N, endpoint, Q_MAP, rows);
            benchmark("prob:cdf", "Hist", p, N, endpoint, Q_CDF, rows);
            benchmark("prob:jsd", "Hist", p, N, endpoint, Q_JSD, rows);
        }

        for (int k : DEMO_MODE ? new int[]{4} : new int[]{4, 10, 20}) {
            String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_micro_dir_k" + k);
            String p = "k=" + k;
            benchmark("prob:mean", "Dir", p, N, endpoint, Q_MEAN, rows);
            benchmark("prob:std", "Dir", p, N, endpoint, Q_STD, rows);
            benchmark("prob:map", "Dir", p, N, endpoint, Q_MAP, rows);
            benchmark("prob:cdf", "Dir", p, N, endpoint, Q_CDF_DIR, rows);
            benchmark("prob:jsd", "Dir", p, N, endpoint, Q_JSD, rows);
        }

        writeCsv(outputDir + "/exp4_micro.csv", rows);
        System.out.println("Results -> " + outputDir + "/exp4_micro.csv");
    }

    private static void benchmark(String fn, String type, String param, int n,
                                  String endpoint, String sparql, List<String[]> rows) {
        QueryFactory.create(sparql);
        for (int i = 0; i < WARMUP_RUNS; i++) {
            RemoteBenchmarkClient.execCount(endpoint, sparql);
        }

        long[] times = new long[TIMED_RUNS];
        for (int i = 0; i < TIMED_RUNS; i++) {
            long t0 = System.nanoTime();
            RemoteBenchmarkClient.execCount(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        long medTotalNs = times[TIMED_RUNS / 2];
        long iqrTotalNs = times[TIMED_RUNS * 3 / 4] - times[TIMED_RUNS / 4];
        double perCallUs = medTotalNs / 1_000.0 / n;
        double iqrUs = iqrTotalNs / 1_000.0 / n;

        System.out.printf("  %-12s  %-5s  %-6s  median=%.2f us  iqr=%.2f us%n",
                fn, type, param, perCallUs, iqrUs);
        rows.add(new String[]{fn, type, param, String.valueOf(n), fmt(perCallUs), fmt(iqrUs)});
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }
}
