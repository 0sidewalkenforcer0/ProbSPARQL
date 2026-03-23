package org.apache.jena.probsparql;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.graph.Node;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Experiment 2 v5 — Filter-Pushdown Advantage with Mixed-K Datasets
 *
 * <p>Three approaches are compared in-process (same JVM, same GT_10K JSD):
 * <ul>
 *   <li><b>A — Naive SPARQL with modeCount pre-filter</b>:
 *       Uses FILTER(prob:modeCount &gt; 1) before BIND(jsdivergence) in query.</li>
 *   <li><b>B — In-process Java loop</b>:
 *       Fetches all pairs via bare BGP, then applies F1/F2 (modeCount) and F3
 *       (JSD) in a sequential Java loop. The "same language" baseline.</li>
 *   <li><b>C — Pruned SIMILARITYJOIN with modeCount in sub-patterns</b>:
 *       Uses filter-pushdown into iterator + discretized JSD lower bound pruning.</li>
 * </ul>
 *
 * <p>Experimental variable: {@code unimodalFrac} — the fraction of entities with
 * K=1 (unimodal). As unimodalFrac increases, the modeCount pre-filter eliminates
 * more work before the expensive JSD computation, amplifying C's advantage.
 *
 * <p>Design: {@code N_PAIRS × UNIMODAL_FRACS × SEL_LABELS} = 45 configurations.
 * Each configuration runs WARMUP=1 + RUNS=3 for each approach; median is reported.
 * Calibration uses multimodal-only pair JSD values.
 *
 * <p>Output files (written to {@code --output-dir}):
 * <pre>
 *   exp2v5_calibration.csv   — per-scale/fraction θ values
 *   exp2v5_a.csv             — Approach A timings
 *   exp2v5_b_java.csv        — Approach B in-process Java timings
 *   exp2v5_c.csv             — Approach C timings
 *   exp2v5_pruning_stats.csv — per-level pruning counts for Approach C
 * </pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp2BenchmarkV5" \
 *       -Dexec.args="--output-dir benchmark/results/exp2_v5"
 */
public class Exp2BenchmarkV5 {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    private static int WARMUP_RUNS    = 3;
    private static int BENCHMARK_RUNS = 10;

    /** Target pair counts. nEntities = ceil((1 + sqrt(1 + 8*N)) / 2) */
    private static final int[] N_PAIRS = {100, 500, 1000, 5000, 10_000};

    /** Fraction of entities with K=1 (unimodal). Rest have K=3 (multimodal). */
    private static final double[] UNIMODAL_FRACS = {0.2, 0.5, 0.8};

    private static final int K_UNIMODAL   = 1;
    private static final int K_MULTIMODAL = 3;

    private static final String[] SEL_LABELS      = {"10pct", "50pct", "90pct"};
    private static final double[] SEL_PERCENTILES  = {0.10, 0.50, 0.90};

    private static final String NS_EX = "http://example.org/data/";
    private static final String NS_UQ = "http://example.org/ontology/uncertainty#";

    // -----------------------------------------------------------------------
    // SPARQL query templates
    // -----------------------------------------------------------------------

    /**
     * Approach A: SPARQL with modeCount pre-filters then JSD FILTER.
     * The two FILTER(prob:modeCount &gt; 1) calls short-circuit before the
     * expensive jsdivergence BIND+FILTER for unimodal entities.
     */
    private static String qNaive(double theta) {
        return String.format("""
            PREFIX uq:   <http://example.org/ontology/uncertainty#>
            PREFIX prob: <http://probsparql.org/function#>
            SELECT ?rv1 ?rv2 WHERE {
                ?rv1 uq:hasDistribution ?d1 .
                FILTER(prob:modeCount(?d1) > 1)
                ?rv2 uq:hasDistribution ?d2 .
                FILTER(prob:modeCount(?d2) > 1)
                FILTER(str(?rv1) < str(?rv2))
                BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)
                FILTER(?jsd <= %.6f)
            }""", theta);
    }

    /**
     * Calibration: collect JSD values only for multimodal-multimodal pairs.
     * Calibrating on the same universe as what A/B/C actually compare.
     */
    private static final String Q_COLLECT_MULTIMODAL = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?jsd WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
            FILTER(prob:modeCount(?d1) > 1)
            FILTER(prob:modeCount(?d2) > 1)
            BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)
        }""";

    /**
     * Approach B: bare BGP export — no modeCount filter, no threshold, all pairs.
     * The Java loop then computes JSD for every pair without any pre-filtering.
     * This models a naive external processor that lacks filter-pushdown capability.
     */
    private static final String Q_FETCH_BARE = """
        PREFIX uq: <http://example.org/ontology/uncertainty#>
        SELECT ?rv1 ?rv2 ?d1 ?d2 WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
        }""";

    /**
     * Approach C: SIMILARITYJOIN with modeCount pushed into sub-patterns.
     * The iterator only sees multimodal entities; the pruner's discretized JSD
     * lower bound prunes (multimodal, multimodal) pairs cheaply before full JSD.
     */
    private static String qSimJoin(double theta) {
        return String.format("""
            PREFIX uq:   <http://example.org/ontology/uncertainty#>
            PREFIX prob: <http://probsparql.org/function#>
            SELECT ?rv1 ?rv2 WHERE {
              { ?rv1 uq:hasDistribution ?d1 . FILTER(prob:modeCount(?d1) > 1) . }
              SIMILARITYJOIN(?d1, ?d2, %.6f)
              { ?rv2 uq:hasDistribution ?d2 . FILTER(prob:modeCount(?d2) > 1) . }
            }""", theta);
    }

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        // Mode is applied after argument parsing (see below)

        String outputDir  = "benchmark/results/exp2_v5";
        String jsdMode    = System.getProperty("probsparql.mode", "GT_1K");
        for (int i = 0; i < args.length; i++) {
            if ("--output-dir".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                jsdMode = args[++i];
            } else if ("--warmup".equals(args[i]) && i + 1 < args.length) {
                WARMUP_RUNS = Integer.parseInt(args[++i]);
            } else if ("--runs".equals(args[i]) && i + 1 < args.length) {
                BENCHMARK_RUNS = Integer.parseInt(args[++i]);
            }
        }
        // Apply chosen mode (allows --mode GT_10K override on command line)
        System.setProperty("probsparql.mode", jsdMode);
        org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig.reloadMode();
        new File(outputDir).mkdirs();

        System.out.println("=== Exp2BenchmarkV5: Filter-Pushdown vs External Java (3-way) ===");
        System.out.printf("Warmup=%d  Runs=%d  K_uni=%d  K_multi=%d  Mode=%s%n%n",
            WARMUP_RUNS, BENCHMARK_RUNS, K_UNIMODAL, K_MULTIMODAL, jsdMode);

        // CSV row lists
        List<String[]> calibRows   = new ArrayList<>();
        List<String[]> aRows       = new ArrayList<>();
        List<String[]> bJavaRows   = new ArrayList<>();
        List<String[]> cRows       = new ArrayList<>();
        List<String[]> pruningRows = new ArrayList<>();

        calibRows.add(new String[]{
            "NPairs", "UnimodalFrac", "TotalPairs", "MultimodalPairs",
            "Theta_10pct", "Theta_50pct", "Theta_90pct"});
        aRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        bJavaRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount",
            "Note"});  // Note: ResultCount includes unimodal-involving pairs
        cRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        pruningRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta",
            "TotalPairs", "PrunedDim", "PrunedMean", "PrunedVar", "PrunedBounds",
            "FullJSD", "ResultCount", "PruningRate"});

        for (int nPairs : N_PAIRS) {
            int n = (int) Math.ceil((1.0 + Math.sqrt(1.0 + 8.0 * nPairs)) / 2.0);

            for (double unimodalFrac : UNIMODAL_FRACS) {
                System.out.printf("════ nPairs≈%d  n=%d  unimodalFrac=%.1f ══════════%n",
                    nPairs, n, unimodalFrac);

                Dataset ds = buildDataset(n, unimodalFrac);

                int actualPairs = n * (n - 1) / 2;
                int nMultimodal = n - (int)(n * unimodalFrac);
                int multimodalPairs = nMultimodal * (nMultimodal - 1) / 2;

                // ── Calibration (multimodal-only JSD) ────────────────────
                double[] thetas = calibrate(ds);
                System.out.printf(
                    "  Calib: θ₁₀=%.4f  θ₅₀=%.4f  θ₉₀=%.4f  (mm_pairs=%d)%n",
                    thetas[0], thetas[1], thetas[2], multimodalPairs);
                calibRows.add(new String[]{
                    str(nPairs), fmt(unimodalFrac),
                    str(actualPairs), str(multimodalPairs),
                    fmt(thetas[0]), fmt(thetas[1]), fmt(thetas[2])
                });

                for (int si = 0; si < SEL_LABELS.length; si++) {
                    String sel   = SEL_LABELS[si];
                    double theta = thetas[si];

                    System.out.printf("  ── sel=%s  θ=%.4f ──────────────────%n", sel, theta);

                    // ── Approach A ───────────────────────────────────────
                    TimingResult rA = measureMedian(ds, qNaive(theta));
                    System.out.printf("     A (sparql modeCount+jsd) : %.2f ms  (%d results)%n",
                        rA.timeMs, rA.resultCount);
                    aRows.add(new String[]{
                        str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                        fmt(rA.timeMs), str(rA.resultCount)});

                    // ── Approach B (naive Java loop, no modeCount filter) ─
                    TimingResult rB = measureExternalJava(ds, theta);
                    System.out.printf("     B (java naive, all pairs): %.2f ms  (%d results)%n",
                        rB.timeMs, rB.resultCount);
                    bJavaRows.add(new String[]{
                        str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                        fmt(rB.timeMs), str(rB.resultCount),
                        "all_pairs_no_modeCount_filter"});

                    // ── Approach C (SIMILARITYJOIN) ───────────────────────
                    System.setProperty("probsparql.simjoin.pruning",     "true");
                    System.setProperty("probsparql.simjoin.deduplicate", "true");
                    try {
                        TimingResult rC = measureWithPruning(ds, qSimJoin(theta));
                        System.out.printf("     C (simjoin, pruning=%.1f%%): %.2f ms  (%d results)%n",
                            rC.pruning != null ? rC.pruning.pruningRate() * 100 : 0.0,
                            rC.timeMs, rC.resultCount);

                        cRows.add(new String[]{
                            str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            fmt(rC.timeMs), str(rC.resultCount)});

                        if (rC.pruning != null) {
                            PruningStats ps = rC.pruning;
                            pruningRows.add(new String[]{
                                str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                                str(ps.totalPairs), str(ps.prunedByDim), str(ps.prunedByMean),
                                str(ps.prunedByVariance), str(ps.prunedByBounds),
                                str(ps.computedFullJSD), str(ps.resultCount),
                                fmt(ps.pruningRate())
                            });
                        }

                        // Sanity: A==B==C result count (within 1%)
                        checkResultConsistency(rA, rB, rC, nPairs, unimodalFrac, sel);

                    } finally {
                        System.setProperty("probsparql.simjoin.pruning",     "false");
                        System.clearProperty("probsparql.simjoin.deduplicate");
                    }
                }

                ds.close();
                System.out.println();
            }
        }

        // Write all CSVs
        writeCsv(outputDir + "/exp2v5_calibration.csv",   calibRows);
        writeCsv(outputDir + "/exp2v5_a.csv",             aRows);
        writeCsv(outputDir + "/exp2v5_b_java.csv",        bJavaRows);
        writeCsv(outputDir + "/exp2v5_c.csv",             cRows);
        writeCsv(outputDir + "/exp2v5_pruning_stats.csv", pruningRows);

        System.out.println("Results written to: " + outputDir);
    }

    // -----------------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------------

    /**
     * Run Q_COLLECT_MULTIMODAL and return θ at the 10th, 50th, 90th percentiles.
     * Only considers multimodal-multimodal pairs, matching approaches A/B/C.
     */
    private static double[] calibrate(Dataset ds) {
        Query q = QueryFactory.create(Q_COLLECT_MULTIMODAL);
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

    /** Execute query WARMUP+BENCHMARK times; report median of BENCHMARK runs. */
    private static TimingResult measureMedian(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double ms = times[times.length / 2] / 1_000_000.0;
        return new TimingResult(ms, cnt);
    }

    /**
     * Approach B: naive in-process Java loop — NO modeCount pre-filter.
     * 1. Execute Q_FETCH_BARE to get ALL deduplicated pairs with GMM literals.
     * 2. In Java: compute JSD for every pair (including unimodal-involving ones),
     *    then apply threshold filter.
     *
     * <p>This is the "no-filter-pushdown" baseline: it cannot skip unimodal pairs
     * early, so it pays the full JSD cost for all n*(n-1)/2 pairs regardless of
     * modeCount. Contrast with A, which uses SPARQL filter pushdown to skip those
     * pairs before the expensive JSD call.
     */
    @SuppressWarnings("unused")  // resultCount intentionally differs from A
    private static TimingResult measureExternalJava(Dataset ds, double theta) {
        Query fetchQ = QueryFactory.create(Q_FETCH_BARE);
        for (int i = 0; i < WARMUP_RUNS; i++) runJavaLoop(ds, fetchQ, theta);
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = runJavaLoop(ds, fetchQ, theta);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double ms = times[times.length / 2] / 1_000_000.0;
        return new TimingResult(ms, cnt);
    }

    /**
     * Naive Java loop: compute JSD for EVERY pair, no modeCount pre-filter.
     * This deliberately pays the JSD cost for all n*(n-1)/2 pairs to model
     * a processor that lacks filter-pushdown capability.
     */
    private static int runJavaLoop(Dataset ds, Query fetchQ, double theta) {
        int count = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(fetchQ, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                QuerySolution row = rs.next();
                Node d1Node = row.getLiteral("d1").asNode();
                Node d2Node = row.getLiteral("d2").asNode();
                // No modeCount pre-filter — compute JSD for every pair
                double jsd = ProbSPARQL.JSDivergence(d1Node, d2Node);
                if (jsd <= theta) count++;
            }
        }
        return count;
    }

    /**
     * Time Approach C with pruning enabled; also collects PruningStats.
     */
    private static TimingResult measureWithPruning(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);
        long[] times = new long[BENCHMARK_RUNS];
        int cnt = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            cnt = execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double ms = times[times.length / 2] / 1_000_000.0;

        // Extra instrumented execution to collect pruning stats
        PruningStats ps = collectPruningStats(ds, q);
        TimingResult r = new TimingResult(ms, cnt);
        r.pruning = ps;
        return r;
    }

    private static PruningStats collectPruningStats(Dataset ds, Query q) {
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rs.next();
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
    // Sanity check
    // -----------------------------------------------------------------------

    private static void checkResultConsistency(TimingResult rA, TimingResult rB,
                                                TimingResult rC,
                                                int nPairs, double unimodalFrac, String sel) {
        int a = rA.resultCount;
        int b = rB.resultCount;
        int c = rC.resultCount;

        // B counts ALL pairs (including unimodal-involving) with JSD <= theta,
        // so B >= A is expected and normal.  Only flag if B < A (would be a bug).
        if (b < a) {
            System.out.printf(
                "  [WARN] B=%d < A=%d (B should cover superset) at nPairs=%d uf=%.1f sel=%s%n",
                b, a, nPairs, unimodalFrac, sel);
        }

        // C should match A exactly (100% recall with valid DPI bound)
        if (a != c) {
            double recallC = a > 0 ? (double) c / a : (c == 0 ? 1.0 : 0.0);
            System.out.printf(
                "  [WARN] C recall=%.1f%% (%d/%d) at nPairs=%d uf=%.1f sel=%s%n",
                recallC * 100, c, a, nPairs, unimodalFrac, sel);
        }
    }

    // -----------------------------------------------------------------------
    // Dataset builder
    // -----------------------------------------------------------------------

    /**
     * Build a dataset with {@code n} entities.
     * The first {@code (int)(n * unimodalFrac)} entities get K=1 (unimodal);
     * the remainder get K=3 (multimodal).
     */
    private static Dataset buildDataset(int n, double unimodalFrac) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        Resource rvType  = m.createResource(NS_UQ + "RandomVariable");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int nUnimodal = (int)(n * unimodalFrac);
        for (int i = 1; i <= n; i++) {
            Resource rv = m.createResource(NS_EX + "rv5_" + i);
            rv.addProperty(RDF.type, rvType);
            int k = (i <= nUnimodal) ? K_UNIMODAL : K_MULTIMODAL;
            rv.addProperty(hasDist,
                m.createTypedLiteral(makeGmmJson(k, rng), NS_UQ + "gmmLiteral"));
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

    private static String str(long v) { return String.valueOf(v); }
    private static String str(int v)  { return String.valueOf(v); }

    // -----------------------------------------------------------------------
    // Result holder
    // -----------------------------------------------------------------------

    static class TimingResult {
        double timeMs;
        int    resultCount;
        PruningStats pruning = null;

        TimingResult(double timeMs, int resultCount) {
            this.timeMs      = timeMs;
            this.resultCount = resultCount;
        }
    }
}
