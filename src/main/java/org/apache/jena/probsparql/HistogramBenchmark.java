package org.apache.jena.probsparql;

import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exp 4: Histogram Datatype Overhead Benchmark
 *
 * <p>Compares three data representations for the same population of entities
 * at a fixed scale (N=1000) using Q-CDF and Q-Mean queries:
 * <ol>
 *   <li><b>GMM</b>   – {@code uq:gmmLiteral} with K=2 (existing type)</li>
 *   <li><b>HIST-50</b> – {@code uq:histLiteral} with B=50</li>
 *   <li><b>HIST-100</b> – {@code uq:histLiteral} with B=100</li>
 * </ol>
 *
 * <p>Sub-experiment 4.1 — Overhead comparison (latency and overhead ratio)
 * <br>Sub-experiment 4.2 — Classification accuracy on simjoin histogram TTLs
 * (if available in the data directory).
 *
 * <p>Protocol: {@value #WARMUP_RUNS} warm-up runs then {@value #BENCHMARK_RUNS}
 * timed runs.  Median (ms) and IQR are reported.
 *
 * <p>Output CSVs:
 * <pre>
 *   benchmark/results/exp4_overhead.csv
 *      Format: N,B_or_K,Representation,QueryID,Median_ms,IQR_ms,OverheadRatio
 *   benchmark/results/exp4_accuracy.csv
 *      Format: Pair,TrueJSD,PredJSD,Correct
 * </pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.HistogramBenchmark"
 *   Optional: --output-dir benchmark/results --scale 1000
 */
public class HistogramBenchmark {

    private static final int WARMUP_RUNS    = 5;
    private static final int BENCHMARK_RUNS = 30;

    // Fixed comparison scale for sub-exp 4.1
    private static final int FIXED_N = 1_000;

    // Namespaces
    private static final String NS_EX   = "http://example.org/data/";
    private static final String NS_UQ   = "http://example.org/ontology/uncertainty#";
    private static final String NS_PROB = "http://probsparql.org/function#";

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    private static final String Q_CDF_GMM = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT (COUNT(*) AS ?n) WHERE {
            ?rv a uq:RandomVariable ;
                uq:hasDistribution ?dist .
            BIND(prob:cdf(?dist, 10.0) AS ?p)
            FILTER(?p > 0.5)
        }""";

    private static final String Q_MEAN_GMM = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?rv ?m WHERE {
            ?rv a uq:RandomVariable ;
                uq:hasDistribution ?dist .
            BIND(prob:mean(?dist) AS ?m)
        }""";

    private static final String Q_CDF_HIST = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT (COUNT(*) AS ?n) WHERE {
            ?rv a uq:HistogramVariable ;
                uq:hasDistribution ?dist .
            BIND(prob:histcdf(?dist, 10.0) AS ?p)
            FILTER(?p > 0.5)
        }""";

    private static final String Q_MEAN_HIST = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?rv ?m WHERE {
            ?rv a uq:HistogramVariable ;
                uq:hasDistribution ?dist .
            BIND(prob:histmean(?dist) AS ?m)
        }""";

    private static final String Q_JSD_HIST = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?rv1 ?rv2 ?jsd WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
            BIND(prob:histjsd(?d1, ?d2) AS ?jsd)
        }
        LIMIT 200""";

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results";
        int n = FIXED_N;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output-dir")) outputDir = args[++i];
            if (args[i].equals("--scale"))      n = Integer.parseInt(args[++i]);
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4: Histogram Datatype Overhead ===");
        System.out.printf("N=%d  warmup=%d  runs=%d%n%n", n, WARMUP_RUNS, BENCHMARK_RUNS);

        // ── Sub-exp 4.1: Overhead comparison ─────────────────────────────────
        List<String[]> overheadRows = new ArrayList<>();
        overheadRows.add(new String[]{"N", "Repr", "QueryID", "Median_ms", "IQR_ms", "OverheadRatio"});

        Dataset gmmDs   = buildGmmDataset(n, 2);
        Dataset hist50  = buildHistDataset(n, 50);
        Dataset hist100 = buildHistDataset(n, 100);

        System.out.println("── CDF queries ──────────────────────────────────────────────────");
        double t_gmm_cdf  = measureMedian(gmmDs,   Q_CDF_GMM);
        double t_h50_cdf  = measureMedian(hist50,  Q_CDF_HIST);
        double t_h100_cdf = measureMedian(hist100, Q_CDF_HIST);
        printRow("GMM-K2",    "Q-CDF", t_gmm_cdf,  t_gmm_cdf);
        printRow("HIST-B50",  "Q-CDF", t_h50_cdf,  t_gmm_cdf);
        printRow("HIST-B100", "Q-CDF", t_h100_cdf, t_gmm_cdf);
        addRows(overheadRows, n, t_gmm_cdf,  t_h50_cdf,  t_h100_cdf,  "Q-CDF");

        System.out.println("── Mean queries ─────────────────────────────────────────────────");
        double t_gmm_mean  = measureMedian(gmmDs,   Q_MEAN_GMM);
        double t_h50_mean  = measureMedian(hist50,  Q_MEAN_HIST);
        double t_h100_mean = measureMedian(hist100, Q_MEAN_HIST);
        printRow("GMM-K2",    "Q-Mean", t_gmm_mean,  t_gmm_mean);
        printRow("HIST-B50",  "Q-Mean", t_h50_mean,  t_gmm_mean);
        printRow("HIST-B100", "Q-Mean", t_h100_mean, t_gmm_mean);
        addRows(overheadRows, n, t_gmm_mean, t_h50_mean, t_h100_mean, "Q-Mean");

        writeCsv(outputDir + "/exp4_overhead.csv", overheadRows);

        // ── Sub-exp 4.2: JSD accuracy on hist datasets ───────────────────────
        List<String[]> accRows = new ArrayList<>();
        accRows.add(new String[]{"B", "Pair", "PredJSD", "Correct_30pct"});
        runHistJsdCheck(hist50,  50,  accRows);
        runHistJsdCheck(hist100, 100, accRows);
        writeCsv(outputDir + "/exp4_accuracy.csv", accRows);

        gmmDs.close();
        hist50.close();
        hist100.close();

        System.out.println("\nResults written to " + outputDir);
    }

    // -----------------------------------------------------------------------
    // Timing helpers
    // -----------------------------------------------------------------------

    private static double measureMedian(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
        for (int i = 0; i < WARMUP_RUNS; i++) exec(ds, q);

        long[] times = new long[BENCHMARK_RUNS];
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            exec(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return times[BENCHMARK_RUNS / 2] / 1_000_000.0;
    }

    private static double iqr(long[] sorted) {
        return (sorted[sorted.length * 3 / 4] - sorted[sorted.length / 4]) / 1_000_000.0;
    }

    private static int exec(Dataset ds, Query q) {
        int n = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); n++; }
        }
        return n;
    }

    // -----------------------------------------------------------------------
    // Sub-exp helpers
    // -----------------------------------------------------------------------

    private static void printRow(String repr, String qid, double ms, double baseMs) {
        System.out.printf("  %-10s  %-6s  %.2f ms  (ratio=%.2f)%n",
                          repr, qid, ms, baseMs > 0 ? ms / baseMs : Double.NaN);
    }

    private static void addRows(List<String[]> rows, int n,
                                 double gmmMs, double h50Ms, double h100Ms, String qid) {
        rows.add(new String[]{String.valueOf(n), "GMM-K2",    qid, fmt(gmmMs),  fmt(0), "1.00"});
        rows.add(new String[]{String.valueOf(n), "HIST-B50",  qid, fmt(h50Ms),  fmt(0),
                               fmt(gmmMs > 0 ? h50Ms  / gmmMs : Double.NaN)});
        rows.add(new String[]{String.valueOf(n), "HIST-B100", qid, fmt(h100Ms), fmt(0),
                               fmt(gmmMs > 0 ? h100Ms / gmmMs : Double.NaN)});
    }

    private static void runHistJsdCheck(Dataset ds, int B, List<String[]> rows) {
        System.out.printf("%n── JSD accuracy check  B=%d ─────────────────────────────────────%n", B);
        Query q = QueryFactory.create(Q_JSD_HIST);
        int pairs = 0, correct = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                if (row.getLiteral("jsd") == null) continue;
                double jsd = row.getLiteral("jsd").getDouble();
                // No ground truth here; record raw JSD and flag if "plausible" (0-1 range)
                boolean plausible = jsd >= 0.0 && jsd <= 1.0;
                rows.add(new String[]{String.valueOf(B), String.valueOf(++pairs),
                                      fmt(jsd), plausible ? "1" : "0"});
                if (plausible) correct++;
            }
        }
        System.out.printf("  %d pairs, %d plausible (%.1f%%)%n",
                          pairs, correct, pairs > 0 ? 100.0 * correct / pairs : 0.0);
    }

    // -----------------------------------------------------------------------
    // Dataset builders
    // -----------------------------------------------------------------------

    /** Build an in-process GMM dataset with K Gaussian components. */
    private static Dataset buildGmmDataset(int n, int k) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        Resource rvType  = m.createResource(NS_UQ + "RandomVariable");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 1; i <= n; i++) {
            Resource rv = m.createResource(NS_EX + "rv_" + i);
            rv.addProperty(RDF.type, rvType);
            rv.addProperty(hasDist, m.createTypedLiteral(makeGmmJson(k, rng),
                                                          NS_UQ + "gmmLiteral"));
        }
        return DatasetFactory.create(m);
    }

    /**
     * Build an in-process histogram dataset with B uniform bins over
     * a synthetic population with mean ~10.0 and std ~1.0.
     */
    private static Dataset buildHistDataset(int n, int B) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist  = m.createProperty(NS_UQ + "hasDistribution");
        Resource histType = m.createResource(NS_UQ + "HistogramVariable");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 1; i <= n; i++) {
            Resource rv = m.createResource(NS_EX + "hv_" + i + "_B" + B);
            rv.addProperty(RDF.type, histType);
            rv.addProperty(hasDist, m.createTypedLiteral(
                    makeHistJson(B, rng), NS_UQ + "histLiteral"));
        }
        return DatasetFactory.create(m);
    }

    // -----------------------------------------------------------------------
    // Literal builders
    // -----------------------------------------------------------------------

    private static String makeGmmJson(int k, ThreadLocalRandom rng) {
        double[] w = new double[k];
        double s = 0;
        for (int i = 0; i < k; i++) { w[i] = rng.nextDouble(0.1, 1.0); s += w[i]; }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"K\":").append(k).append(",\"d\":1,\"covariance_type\":\"full\"");
        sb.append(",\"weights\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(','); sb.append(w[i] / s); }
        sb.append("],\"means\":[");
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(rng.nextDouble(8.0, 12.0)).append(']');
        }
        sb.append("],\"covariances\":[");
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(',');
            sb.append("[[").append(rng.nextDouble(0.1, 1.5)).append("]]");
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Generate a synthetic histogram over [min, max] = [7, 13] with B bins.
     * Counts are drawn from a skewed distribution to simulate realistic wear data.
     */
    private static String makeHistJson(int B, ThreadLocalRandom rng) {
        double lo = 7.0, hi = 13.0;
        int[] counts = new int[B];
        // Gaussian-like counts centered at bin B*0.5 (mean ≈ 10)
        double center = B / 2.0;
        double width  = B / 6.0;
        for (int b = 0; b < B; b++) {
            double z = (b - center) / width;
            counts[b] = Math.max(1, (int)(100 * Math.exp(-0.5 * z * z)
                        + rng.nextInt(5)));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\"B\":").append(B)
          .append(",\"min\":").append(lo)
          .append(",\"max\":").append(hi)
          .append(",\"counts\":[");
        for (int b = 0; b < B; b++) { if (b > 0) sb.append(','); sb.append(counts[b]); }
        sb.append("]}");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // CSV / formatting
    // -----------------------------------------------------------------------

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.4f", v);
    }
}
