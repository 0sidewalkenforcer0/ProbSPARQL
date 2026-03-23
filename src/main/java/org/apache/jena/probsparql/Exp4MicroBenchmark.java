package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exp 4.2 — Per-Type Operation Micro-Benchmark
 *
 * <p>Measures median per-call latency (µs) for each SPARQL function on each
 * distribution type with varying representation parameters.</p>
 *
 * <p>Setup for each cell:
 * <ul>
 *   <li>1,000 distribution literals in an in-memory Jena dataset</li>
 *   <li>3 warmup runs, 10 timed runs</li>
 *   <li>Per-call time = total query time / N (N=1,000 entities)</li>
 * </ul>
 *
 * <p>Output CSV: {@code exp4_micro.csv}
 * <pre>Function,DistType,Param,N,MedianUs,IQRUs</pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp4MicroBenchmark"
 *   Optional: --output-dir benchmark/results/exp4_full
 */
public class Exp4MicroBenchmark {

    private static int N           = 1_000;
    private static int WARMUP_RUNS = 3;
    private static int TIMED_RUNS  = 10;
    private static boolean DEMO_MODE = false;

    private static final String NS_EX   = "http://example.org/data/";
    private static final String NS_UQ   = "http://example.org/ontology/uncertainty#";
    private static final String NS_PROB = "http://probsparql.org/function#";

    // Single-distribution queries
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

    // Pairwise JSD: query only on LIMIT 200 pairs (N=1000 → ~500K pairs; LIMIT manageable)
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

        String outputDir = "benchmark/results/exp4_full";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output-dir")) outputDir = args[++i];
            if (args[i].equals("--demo"))       DEMO_MODE = true;
        }
        if (DEMO_MODE) {
            N = 50; WARMUP_RUNS = 1; TIMED_RUNS = 1;
            System.out.println("  [DEMO MODE: N=50, warmup=1, runs=1]");
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.2: Per-Type Operation Micro-Benchmark ===");
        System.out.printf("  N=%d  warmup=%d  runs=%d  demo=%b%n%n", N, WARMUP_RUNS, TIMED_RUNS, DEMO_MODE);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Function", "DistType", "Param", "N", "MedianUs", "IQRUs"});

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // GMM K=3
        Dataset gmmDs = buildGmmDataset(N, 3, rng);
        benchmark("prob:mean", "GMM",  "K=3", N, gmmDs, Q_MEAN,  rows);
        benchmark("prob:std",  "GMM",  "K=3", N, gmmDs, Q_STD,   rows);
        benchmark("prob:map",  "GMM",  "K=3", N, gmmDs, Q_MAP,   rows);
        benchmark("prob:cdf",  "GMM",  "K=3", N, gmmDs, Q_CDF,   rows);
        benchmark("prob:jsd",  "GMM",  "K=3", N, gmmDs, Q_JSD,   rows);
        gmmDs.close();

        // Histogram B=20, 50, 100 (demo: B=50 only)
        for (int B : DEMO_MODE ? new int[]{50} : new int[]{20, 50, 100}) {
            Dataset histDs = buildHistDataset(N, B, rng);
            String p = "B=" + B;
            benchmark("prob:mean", "Hist", p, N, histDs, Q_MEAN, rows);
            benchmark("prob:std",  "Hist", p, N, histDs, Q_STD,  rows);
            benchmark("prob:map",  "Hist", p, N, histDs, Q_MAP,  rows);
            benchmark("prob:cdf",  "Hist", p, N, histDs, Q_CDF,  rows);
            benchmark("prob:jsd",  "Hist", p, N, histDs, Q_JSD,  rows);
            histDs.close();
        }

        // Dirichlet k=4, 10, 20 (demo: k=4 only)
        for (int k : DEMO_MODE ? new int[]{4} : new int[]{4, 10, 20}) {
            Dataset dirDs = buildDirDataset(N, k, rng);
            String p = "k=" + k;
            benchmark("prob:mean", "Dir", p, N, dirDs, Q_MEAN,    rows);
            benchmark("prob:std",  "Dir", p, N, dirDs, Q_STD,     rows);
            benchmark("prob:map",  "Dir", p, N, dirDs, Q_MAP,     rows);
            benchmark("prob:cdf",  "Dir", p, N, dirDs, Q_CDF_DIR, rows);
            benchmark("prob:jsd",  "Dir", p, N, dirDs, Q_JSD,     rows);
            dirDs.close();
        }

        writeCsv(outputDir + "/exp4_micro.csv", rows);
        System.out.println("Results → " + outputDir + "/exp4_micro.csv");
    }

    // -----------------------------------------------------------------------

    private static void benchmark(String fn, String type, String param, int n,
                                   Dataset ds, String sparql, List<String[]> rows) {
        Query q = QueryFactory.create(sparql);
        // warm-up
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);

        long[] times = new long[TIMED_RUNS];
        for (int i = 0; i < TIMED_RUNS; i++) {
            long t0 = System.nanoTime();
            execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        long medTotalNs = times[TIMED_RUNS / 2];
        long iqrTotalNs = times[TIMED_RUNS * 3 / 4] - times[TIMED_RUNS / 4];
        // Per-call µs (assuming query touched ~N entities; for JSD it's LIMIT 200)
        double perCallUs = medTotalNs / 1_000.0 / n;
        double iqrUs     = iqrTotalNs / 1_000.0 / n;

        System.out.printf("  %-12s  %-5s  %-6s  median=%.2f µs  iqr=%.2f µs%n",
                fn, type, param, perCallUs, iqrUs);
        rows.add(new String[]{fn, type, param, String.valueOf(n),
                fmt(perCallUs), fmt(iqrUs)});
    }

    private static int execCount(Dataset ds, Query q) {
        int n = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); n++; }
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // Dataset builders
    // -----------------------------------------------------------------------

    private static Dataset buildGmmDataset(int n, int k, ThreadLocalRandom rng) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        for (int i = 1; i <= n; i++) {
            Resource e = m.createResource(NS_EX + "gmm_" + i);
            e.addProperty(hasDist, m.createTypedLiteral(makeGmmJson(k, rng), NS_UQ + "gmmLiteral"));
        }
        return DatasetFactory.create(m);
    }

    private static Dataset buildHistDataset(int n, int B, ThreadLocalRandom rng) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        for (int i = 1; i <= n; i++) {
            Resource e = m.createResource(NS_EX + "hist_" + i + "_B" + B);
            e.addProperty(hasDist, m.createTypedLiteral(makeHistJson(B, rng), NS_UQ + "histLiteral"));
        }
        return DatasetFactory.create(m);
    }

    private static Dataset buildDirDataset(int n, int k, ThreadLocalRandom rng) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        for (int i = 1; i <= n; i++) {
            Resource e = m.createResource(NS_EX + "dir_" + i + "_k" + k);
            e.addProperty(hasDist, m.createTypedLiteral(makeDirJson(k, rng), NS_UQ + "dirichletLiteral"));
        }
        return DatasetFactory.create(m);
    }

    // -----------------------------------------------------------------------
    // Literal builders
    // -----------------------------------------------------------------------

    private static String makeGmmJson(int k, ThreadLocalRandom rng) {
        double[] w = new double[k]; double wSum = 0;
        for (int i = 0; i < k; i++) { w[i] = rng.nextDouble(0.1, 1.0); wSum += w[i]; }
        StringBuilder sb = new StringBuilder("{\"K\":" + k + ",\"d\":1,\"covariance_type\":\"diag\",\"weights\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("%.6f", w[i]/wSum)); }
        sb.append("],\"means\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("[%.4f]", rng.nextDouble(13.0,17.0))); }
        sb.append("],\"covariances\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("[%.6f]", rng.nextDouble(0.001,0.1))); }
        sb.append("]}");
        return sb.toString();
    }

    // Fixed bin range so pairwise prob:jsd comparisons don't fail on incompatible bins
    private static final double HIST_LO = 5.0, HIST_HI = 25.0;

    private static String makeHistJson(int B, ThreadLocalRandom rng) {
        StringBuilder sb = new StringBuilder("{\"B\":" + B + ",\"min\":" + String.format("%.4f", HIST_LO)
                + ",\"max\":" + String.format("%.4f", HIST_HI) + ",\"counts\":[");
        for (int i = 0; i < B; i++) { if (i > 0) sb.append(","); sb.append(rng.nextInt(1,50)); }
        sb.append("]}");
        return sb.toString();
    }

    private static String makeDirJson(int k, ThreadLocalRandom rng) {
        StringBuilder sb = new StringBuilder("{\"type\":\"dirichlet\",\"k\":" + k + ",\"alpha\":[");
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format("%.4f", rng.nextDouble(0.5, 5.0)));
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String fmt(double v) { return String.format("%.4f", v); }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
        System.out.println("CSV written: " + path);
    }
}
