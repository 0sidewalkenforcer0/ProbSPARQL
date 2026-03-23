package org.apache.jena.probsparql;

import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Experiment 2 — In-Engine vs External (three-way comparison)
 *
 * <p>Three approaches:
 * <ul>
 *   <li><b>A — Naive in-engine</b>: BIND(prob:jsdivergence) + FILTER inside SPARQL</li>
 *   <li><b>B — External (fetch)</b>: bare BGP export; Python computes JSD externally</li>
 *   <li><b>C — Pruned SimJoin</b>: SIMILARITYJOIN with 5-level pruning cascade</li>
 * </ul>
 *
 * <p>Thresholds are calibrated per-scale using percentile-based selectivity
 * (10 %, 50 %, 90 %) rather than fixed values.
 *
 * <p>Output files (written to {@code --output-dir}):
 * <pre>
 *   exp2_calibration.csv   — per-scale θ values
 *   exp2_a.csv             — Approach A timings
 *   exp2_b_fetch.csv       — Approach B fetch timings + export size
 *   exp2_c.csv             — Approach C timings
 *   exp2_pruning_stats.csv — per-level pruning counts for Approach C
 *   exp2_pairs_{N}.json    — exported GMM pairs for Python external baseline
 * </pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp2Benchmark" \
 *       -Dexec.args="--output-dir benchmark/results/exp2"
 */
public class Exp2Benchmark {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    private static final int WARMUP_RUNS    = 1;
    private static final int BENCHMARK_RUNS = 1;

    /** Target pair counts. nEntities = ceil((1 + sqrt(1 + 8*N)) / 2) */
    private static final int[] N_PAIRS = {100, 500, 1000, 5000, 10_000};

    private static final int K_COMPONENTS = 3;   // GMM components (fixed for this experiment)

    // Selectivity labels must match order of percentiles in calibration
    private static final String[] SEL_LABELS    = {"10pct", "50pct", "90pct"};
    private static final double[] SEL_PERCENTILES = {0.10, 0.50, 0.90};

    private static final String NS_EX   = "http://example.org/data/";
    private static final String NS_UQ   = "http://example.org/ontology/uncertainty#";

    // -----------------------------------------------------------------------
    // SPARQL query templates
    // -----------------------------------------------------------------------

    /** Approach A: naive in-engine filter. */
    private static String qNaive(double theta) {
        return String.format("""
            PREFIX uq:   <http://example.org/ontology/uncertainty#>
            PREFIX prob: <http://probsparql.org/function#>
            SELECT ?rv1 ?rv2 WHERE {
                ?rv1 uq:hasDistribution ?d1 .
                ?rv2 uq:hasDistribution ?d2 .
                FILTER(str(?rv1) < str(?rv2))
                BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)
                FILTER(?jsd <= %.6f)
            }""", theta);
    }

    /** Calibration: collect all JSD values (no threshold). */
    private static final String Q_COLLECT_ALL = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?jsd WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
            BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)
        }""";

    /** Approach B Step 1: bare BGP export. */
    private static final String Q_FETCH = """
        PREFIX uq: <http://example.org/ontology/uncertainty#>
        SELECT ?rv1 ?rv2 ?d1 ?d2 WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
        }""";

    /** Approach C: pruned SIMILARITYJOIN (relational semantics).
     *  No outer FILTER needed — deduplication is handled inside the operator
     *  when probsparql.simjoin.deduplicate=true.
     */
    private static String qSimJoin(double theta) {
        return String.format("""
            PREFIX uq: <http://example.org/ontology/uncertainty#>
            SELECT ?rv1 ?rv2 WHERE {
              { ?rv1 uq:hasDistribution ?d1 . }
              SIMILARITYJOIN(?d1, ?d2, %.6f)
              { ?rv2 uq:hasDistribution ?d2 . }
            }""", theta);
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        // Force GT_10K for all JSD computations (calibration, Approach A, and C L5 fallback)
        System.setProperty("probsparql.mode", "GT_10K");
        org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig.reloadMode();

        String outputDir = "benchmark/results/exp2";
        for (int i = 0; i < args.length; i++) {
            if ("--output-dir".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            }
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp2Benchmark: In-Engine vs External (3-way) ===");
        System.out.printf("Warmup=%d  Runs=%d  K=%d%n%n", WARMUP_RUNS, BENCHMARK_RUNS, K_COMPONENTS);

        // CSV row lists
        List<String[]> calibRows    = new ArrayList<>();
        List<String[]> aRows        = new ArrayList<>();
        List<String[]> bFetchRows   = new ArrayList<>();
        List<String[]> cRows        = new ArrayList<>();
        List<String[]> pruningRows  = new ArrayList<>();

        calibRows.add(new String[]{"NPairs", "TotalPairs", "Theta_10pct", "Theta_50pct", "Theta_90pct"});
        aRows.add(new String[]{"NPairs", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        bFetchRows.add(new String[]{"NPairs", "Selectivity", "Theta", "Fetch_ms", "ExportSizeBytes"});
        cRows.add(new String[]{"NPairs", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        pruningRows.add(new String[]{
            "NPairs", "Selectivity", "Theta",
            "TotalPairs", "PrunedDim", "PrunedMean", "PrunedVar", "PrunedBounds",
            "FullJSD", "ResultCount", "PruningRate"
        });

        for (int nPairs : N_PAIRS) {
            int n = (int) Math.ceil((1.0 + Math.sqrt(1.0 + 8.0 * nPairs)) / 2.0);
            System.out.printf("════ nPairs≈%d  (n=%d entities) ════════════════%n", nPairs, n);

            Dataset ds = buildDataset(n);

            // ── Calibration ──────────────────────────────────────────────
            double[] thetas = calibrate(ds, nPairs);
            int actualPairs = (int)(n * (long)(n - 1) / 2);
            System.out.printf("  Calibration: θ₁₀=%.4f  θ₅₀=%.4f  θ₉₀=%.4f  (pairs≈%d)%n",
                thetas[0], thetas[1], thetas[2], actualPairs);
            calibRows.add(new String[]{
                str(nPairs), str(actualPairs),
                fmt(thetas[0]), fmt(thetas[1]), fmt(thetas[2])
            });

            // Export GMM pairs JSON (for Python external baseline) — done once per scale
            List<String[]> pairsList = exportPairs(ds);
            writePairsJson(outputDir + "/exp2_pairs_" + nPairs + ".json", pairsList);

            for (int si = 0; si < SEL_LABELS.length; si++) {
                String sel   = SEL_LABELS[si];
                double theta = thetas[si];

                System.out.printf("  ── selectivity=%s  θ=%.4f ─────────────%n", sel, theta);

                // ── Approach A ───────────────────────────────────────────
                TimingResult rA = measure(ds, qNaive(theta));
                System.out.printf("     A (naive)   : %.2f ms  (%d results)%n",
                    rA.timeMs, rA.resultCount);
                aRows.add(new String[]{str(nPairs), sel, fmt(theta), fmt(rA.timeMs), str(rA.resultCount)});

                // ── Approach B (fetch) ───────────────────────────────────
                TimingResult rB = measureFetch(ds);
                System.out.printf("     B (fetch)   : %.2f ms  (%d bytes exported)%n",
                    rB.timeMs, rB.exportBytes);
                bFetchRows.add(new String[]{str(nPairs), sel, fmt(theta),
                    fmt(rB.timeMs), str(rB.exportBytes)});

                // ── Approach C (pruned SimJoin) ───────────────────────────
                System.setProperty("probsparql.simjoin.pruning",      "true");
                System.setProperty("probsparql.simjoin.deduplicate",  "true");
                try {
                    TimingResult rC = measureWithPruning(ds, qSimJoin(theta));
                    System.setProperty("probsparql.simjoin.pruning",     "false");
                    System.clearProperty("probsparql.simjoin.deduplicate");

                    System.out.printf("     C (simjoin) : %.2f ms  (%d results)  pruning=%.1f%%%n",
                        rC.timeMs, rC.resultCount,
                        rC.pruning != null ? rC.pruning.pruningRate() * 100 : 0.0);

                    cRows.add(new String[]{str(nPairs), sel, fmt(theta),
                        fmt(rC.timeMs), str(rC.resultCount)});

                    if (rC.pruning != null) {
                        PruningStats ps = rC.pruning;
                        pruningRows.add(new String[]{
                            str(nPairs), sel, fmt(theta),
                            str(ps.totalPairs), str(ps.prunedByDim), str(ps.prunedByMean),
                            str(ps.prunedByVariance), str(ps.prunedByBounds),
                            str(ps.computedFullJSD), str(ps.resultCount),
                            fmt(ps.pruningRate())
                        });
                    }

                    // ── Sanity check ─────────────────────────────────────
                    double diff = Math.abs(rA.resultCount - rC.resultCount);
                    if (rA.resultCount > 0 && diff / rA.resultCount > 0.05) {
                        System.out.printf(
                            "  [WARN] Result count mismatch: A=%d C=%d (%.1f%%)%n",
                            rA.resultCount, rC.resultCount,
                            diff / rA.resultCount * 100.0);
                    }
                } finally {
                    System.setProperty("probsparql.simjoin.pruning",     "false");
                    System.clearProperty("probsparql.simjoin.deduplicate");
                }
            }

            ds.close();
            System.out.println();
        }

        // Write all CSVs
        writeCsv(outputDir + "/exp2_calibration.csv",   calibRows);
        writeCsv(outputDir + "/exp2_a.csv",             aRows);
        writeCsv(outputDir + "/exp2_b_fetch.csv",       bFetchRows);
        writeCsv(outputDir + "/exp2_c.csv",             cRows);
        writeCsv(outputDir + "/exp2_pruning_stats.csv", pruningRows);

        System.out.println("Results written to: " + outputDir);
    }

    // -----------------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------------

    /**
     * Run Q_COLLECT_ALL and return θ at the 10th, 50th, and 90th percentiles.
     * These correspond to 90%, 50%, and 10% selectivity respectively.
     */
    private static double[] calibrate(Dataset ds, int nPairs) {
        Query q = QueryFactory.create(Q_COLLECT_ALL);
        List<Double> jsdValues = new ArrayList<>();
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                if (row.contains("jsd")) {
                    jsdValues.add(row.getLiteral("jsd").getDouble());
                }
            }
        }
        if (jsdValues.isEmpty()) {
            // Degenerate case: no pairs found
            return new double[]{0.1, 0.3, 0.5};
        }
        Collections.sort(jsdValues);
        double[] thetas = new double[SEL_PERCENTILES.length];
        for (int i = 0; i < SEL_PERCENTILES.length; i++) {
            thetas[i] = percentile(jsdValues, SEL_PERCENTILES[i]);
        }
        return thetas;
    }

    private static double percentile(List<Double> sorted, double p) {
        int n = sorted.size();
        if (n == 0) return 0.3;
        int idx = Math.min((int) Math.ceil(p * n) - 1, n - 1);
        if (idx < 0) idx = 0;
        return sorted.get(idx);
    }

    // -----------------------------------------------------------------------
    // Timing helpers
    // -----------------------------------------------------------------------

    private static TimingResult measure(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
        // warm-up
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);
        // timed runs
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double ms = times[BENCHMARK_RUNS / 2] / 1_000_000.0;
        return new TimingResult(ms, cnt);
    }

    private static TimingResult measureFetch(Dataset ds) {
        Query q = QueryFactory.create(Q_FETCH);
        // warm-up
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);
        // timed run — also measure export size (count bytes of literal strings)
        long totalBytes = 0;
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long bytes = 0;
            long t0 = System.nanoTime();
            try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.next();
                    String d1 = row.getLiteral("d1").getString();
                    String d2 = row.getLiteral("d2").getString();
                    bytes += d1.length() + d2.length();
                    cnt++;
                }
            }
            times[i] = System.nanoTime() - t0;
            totalBytes = bytes;
        }
        Arrays.sort(times);
        double ms = times[BENCHMARK_RUNS / 2] / 1_000_000.0;
        TimingResult r = new TimingResult(ms, cnt);
        r.exportBytes = totalBytes;
        return r;
    }

    /**
     * Time Approach C with pruning enabled.
     * Extracts {@link PruningStats} from the last run by piggy-backing on the
     * iterator stats written to a thread-local slot via the system property gate.
     *
     * Because PruningStats live inside QueryIterPrunedSimilarityJoin and are not
     * directly accessible after the query finishes, we collect them by running an
     * extra instrumented execution after the timing loop.
     */
    private static TimingResult measureWithPruning(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
        // warm-up
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);
        // timed runs
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double ms = times[BENCHMARK_RUNS / 2] / 1_000_000.0;

        // Extra run to collect pruning stats via QueryIterPrunedSimilarityJoin
        PruningStats collected = collectPruningStats(ds, q);
        TimingResult r = new TimingResult(ms, cnt);
        r.pruning = collected;
        return r;
    }

    /**
     * Execute the query and extract PruningStats from the iterator.
     * The QueryIterPrunedSimilarityJoin is materialized by the query engine before
     * we see results, so we collect after the first row or at close.
     */
    private static PruningStats collectPruningStats(Dataset ds, Query q) {
        // We use a thread-local exchange: after execution, iterate fully and
        // get stats from the last QueryIterPrunedSimilarityJoin created on this thread.
        // Simpler approach: use the holder set by OpExecutorProbabilistic.
        // Since we don't have a direct hook, run with a wrapped approach:
        // execute the query, collect results, then retrieve from the Exp2PruningHolder.
        int cnt = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); cnt++; }
        }
        PruningStats ps = Exp2PruningHolder.get();
        Exp2PruningHolder.clear();
        return ps;
    }

    private static int execCount(Dataset ds, Query q) {
        int c = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); c++; }
        }
        return c;
    }

    // -----------------------------------------------------------------------
    // Export GMM pairs as JSON
    // -----------------------------------------------------------------------

    private static List<String[]> exportPairs(Dataset ds) {
        List<String[]> pairs = new ArrayList<>();
        Query q = QueryFactory.create(Q_FETCH);
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                String rv1 = row.getResource("rv1").getURI();
                String rv2 = row.getResource("rv2").getURI();
                String d1  = row.getLiteral("d1").getString();
                String d2  = row.getLiteral("d2").getString();
                pairs.add(new String[]{rv1, rv2, d1, d2});
            }
        }
        return pairs;
    }

    private static void writePairsJson(String path, List<String[]> pairs) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("[");
            for (int i = 0; i < pairs.size(); i++) {
                String[] p = pairs.get(i);
                // d1 and d2 are already valid JSON objects
                pw.printf("  {\"rv1\":\"%s\",\"rv2\":\"%s\",\"d1\":%s,\"d2\":%s}%s%n",
                    p[0], p[1], p[2], p[3], i < pairs.size() - 1 ? "," : "");
            }
            pw.println("]");
        }
    }

    // -----------------------------------------------------------------------
    // Dataset builder
    // -----------------------------------------------------------------------

    private static Dataset buildDataset(int n) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        Resource rvType  = m.createResource(NS_UQ + "RandomVariable");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 1; i <= n; i++) {
            Resource rv = m.createResource(NS_EX + "rv2_" + i);
            rv.addProperty(RDF.type, rvType);
            rv.addProperty(hasDist,
                m.createTypedLiteral(makeGmmJson(K_COMPONENTS, rng), NS_UQ + "gmmLiteral"));
        }
        return DatasetFactory.create(m);
    }

    private static String makeGmmJson(int k, ThreadLocalRandom rng) {
        double[] w = new double[k];
        double s = 0;
        for (int i = 0; i < k; i++) { w[i] = rng.nextDouble(0.1, 1.0); s += w[i]; }
        StringBuilder sb = new StringBuilder("{\"K\":").append(k)
            .append(",\"d\":1,\"covariance_type\":\"full\",\"weights\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(','); sb.append(w[i] / s); }
        sb.append("],\"means\":[");
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(',');
            sb.append('[').append(rng.nextDouble(5.0, 15.0)).append(']');
        }
        sb.append("],\"covariances\":[");
        for (int i = 0; i < k; i++) {
            if (i > 0) sb.append(',');
            sb.append("[[").append(rng.nextDouble(0.1, 2.0)).append("]]");
        }
        return sb.append("]}").toString();
    }

    // -----------------------------------------------------------------------
    // I/O helpers
    // -----------------------------------------------------------------------

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
        System.out.println("Wrote: " + path);
    }

    private static String fmt(double v) {
        return Double.isNaN(v) || Double.isInfinite(v) ? "NaN" : String.format("%.6f", v);
    }

    private static String str(long v)   { return String.valueOf(v); }
    private static String str(int v)    { return String.valueOf(v); }

    // -----------------------------------------------------------------------
    // Result holder
    // -----------------------------------------------------------------------

    static class TimingResult {
        double timeMs;
        int    resultCount;
        long   exportBytes = 0;
        PruningStats pruning = null;

        TimingResult(double timeMs, int resultCount) {
            this.timeMs      = timeMs;
            this.resultCount = resultCount;
        }
    }
}
