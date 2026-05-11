package org.apache.jena.probsparql;

import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.query.*;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.Lang;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Experiment 2 — In-engine Filtering vs DIVJOIN with Mixed-K Datasets
 *
 * <p>Three variants are compared in-process (same JVM, same MC10K numeric JSD
 * for ordinary in-engine filters):
 * <ul>
 *   <li><b>InEngine_CheapFirst</b>:
 *       SPARQL with modeCount filters before prob:jsd.</li>
 *   <li><b>InEngine_JSDFirst</b>:
 *       SPARQL with prob:jsd + threshold before modeCount filters.</li>
 *   <li><b>DIVJOIN</b>:
 *       Uses the dedicated similarity-decision iterator with pruning.</li>
 * </ul>
 *
 * <p>Experimental variable: {@code unimodalFrac} — the fraction of entities with
 * K=1 (unimodal). As unimodalFrac increases, the modeCount pre-filter eliminates
 * more work before the expensive JSD computation, amplifying DIVJOIN's advantage.
 *
 * <p>Design: {@code N_PAIRS × UNIMODAL_FRACS × SEL_LABELS} = 45 configurations.
 * Each configuration runs 3 warm-up iterations and 10 measured iterations for
 * each approach by default; the median of measured runs is reported.
 * Calibration uses multimodal-only pair JSD values.
 *
 * <p>Output files (written to {@code --output-dir}):
 * <pre>
 *   exp2_calibration.csv          — per-scale/fraction θ values
 *   exp2_inengine_cheapfirst.csv  — InEngine_CheapFirst timings
 *   exp2_inengine_jsdfirst.csv    — InEngine_JSDFirst timings
 *   exp2_similarityjoin.csv       — DIVJOIN timings (legacy file name)
 *   exp2_pruning_stats.csv        — per-level pruning counts for DIVJOIN
 * </pre>
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp2Benchmark" \
 *       -Dexec.args="--output-dir benchmark/results/exp2 --data-dir benchmark/data/exp2 --query-dir benchmark/queries/exp2"
 */
public class Exp2Benchmark {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    private static int WARMUP_RUNS    = 3;
    private static int BENCHMARK_RUNS = 10;

    /** Target pair counts. nEntities = ceil((1 + sqrt(1 + 8*N)) / 2) */
    private static final int[] DEFAULT_N_PAIRS = {10_000};

    /** Fraction of entities with K=1 (unimodal). Rest have K=3 (multimodal). */
    private static final double[] UNIMODAL_FRACS = {0.2, 0.5, 0.8};

    private static final String[] SEL_LABELS      = {"10pct", "50pct", "90pct"};
    private static final double[] SEL_PERCENTILES  = {0.10, 0.50, 0.90};

    private static final String NS_EX = "http://example.org/data/";
    private static final String NS_UQ = "http://example.org/ontology/uncertainty#";

    // -----------------------------------------------------------------------
    // Query loading
    // -----------------------------------------------------------------------

    private static final String THETA_PLACEHOLDER = "__THETA__";

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        // Mode is applied after argument parsing (see below)

        String outputDir  = "benchmark/results/exp2";
        String dataDir    = "benchmark/data/exp2";
        String queryDir   = "benchmark/queries/exp2";
        String jsdMode    = System.getProperty("probsparql.mode", "GT_10K");
        int[] nPairsList  = DEFAULT_N_PAIRS;
        for (int i = 0; i < args.length; i++) {
            if ("--output-dir".equals(args[i]) && i + 1 < args.length) {
                outputDir = args[++i];
            } else if ("--data-dir".equals(args[i]) && i + 1 < args.length) {
                dataDir = args[++i];
            } else if ("--query-dir".equals(args[i]) && i + 1 < args.length) {
                queryDir = args[++i];
            } else if ("--mode".equals(args[i]) && i + 1 < args.length) {
                jsdMode = args[++i];
            } else if ("--warmup".equals(args[i]) && i + 1 < args.length) {
                WARMUP_RUNS = Integer.parseInt(args[++i]);
            } else if ("--runs".equals(args[i]) && i + 1 < args.length) {
                BENCHMARK_RUNS = Integer.parseInt(args[++i]);
            } else if ("--npairs".equals(args[i]) && i + 1 < args.length) {
                nPairsList = parseIntList(args[++i]);
            }
        }
        // Apply chosen mode (allows --mode GT_10K override on command line)
        System.setProperty("probsparql.mode", jsdMode);
        org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig.reloadMode();
        new File(outputDir).mkdirs();

        System.out.println("=== Exp2Benchmark: ===");
        System.out.printf("Warmup=%d  Runs=%d  Mode=%s%n",
            WARMUP_RUNS, BENCHMARK_RUNS, jsdMode);
        System.out.printf("Dataset dir=%s%n", dataDir);
        System.out.printf("Query dir=%s%n%n", queryDir);

        String qInEngineCheapFirstTemplate = readQueryTemplate(queryDir, "inengine_cheapfirst.sparql");
        String qInEngineJsdFirstTemplate   = readQueryTemplate(queryDir, "inengine_jsdfirst.sparql");
        String qCollectMultimodal          = readQueryTemplate(queryDir, "collect_multimodal.sparql");
        String qDivJoinTemplate            = readQueryTemplate(queryDir, "similarityjoin.sparql");

        // CSV row lists
        List<String[]> calibRows   = new ArrayList<>();
        List<String[]> inEngineCheapFirstRows = new ArrayList<>();
        List<String[]> inEngineJsdFirstRows   = new ArrayList<>();
        List<String[]> divJoinRows            = new ArrayList<>();
        List<String[]> pruningRows = new ArrayList<>();

        calibRows.add(new String[]{
            "NPairs", "UnimodalFrac", "TotalPairs", "MultimodalPairs",
            "Theta_10pct", "Theta_50pct", "Theta_90pct"});
        inEngineCheapFirstRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        inEngineJsdFirstRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        divJoinRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta", "Time_ms", "ResultCount"});
        pruningRows.add(new String[]{
            "NPairs", "UnimodalFrac", "Selectivity", "Theta",
            "TotalPairs", "PrunedDim", "PrunedMean", "PrunedVar", "PrunedBounds",
            "FullJSD", "ResultCount", "PruningRate"});

        for (int nPairs : nPairsList) {
            int n = (int) Math.ceil((1.0 + Math.sqrt(1.0 + 8.0 * nPairs)) / 2.0);

            for (double unimodalFrac : UNIMODAL_FRACS) {
                System.out.printf("════ nPairs≈%d  n=%d  unimodalFrac=%.1f ══════════%n",
                    nPairs, n, unimodalFrac);

                Dataset ds = loadDataset(dataDir, nPairs, unimodalFrac);

                int actualPairs = n * (n - 1) / 2;
                int nMultimodal = n - (int)(n * unimodalFrac);
                int multimodalPairs = nMultimodal * (nMultimodal - 1) / 2;

                // ── Calibration (multimodal-only JSD) ────────────────────
                double[] thetas = calibrate(ds, qCollectMultimodal);
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

                    // ── InEngine_CheapFirst ──────────────────────────────
                    TimingResult rInEngineCheapFirst = measureMedian(ds, withTheta(qInEngineCheapFirstTemplate, theta));
                    System.out.printf("     InEngine_CheapFirst        : %.2f ms  (%d results)%n",
                        rInEngineCheapFirst.timeMs, rInEngineCheapFirst.resultCount);
                    inEngineCheapFirstRows.add(new String[]{
                        str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                        fmt(rInEngineCheapFirst.timeMs), str(rInEngineCheapFirst.resultCount)});

                    // ── InEngine_JSDFirst ────────────────────────────────
                    TimingResult rInEngineJsdFirst = measureMedian(ds, withTheta(qInEngineJsdFirstTemplate, theta));
                    System.out.printf("     InEngine_JSDFirst          : %.2f ms  (%d results)%n",
                        rInEngineJsdFirst.timeMs, rInEngineJsdFirst.resultCount);
                    inEngineJsdFirstRows.add(new String[]{
                        str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                        fmt(rInEngineJsdFirst.timeMs), str(rInEngineJsdFirst.resultCount)});

                    // ── DIVJOIN ───────────────────────────────────────────
                    System.setProperty("probsparql.simjoin.pruning",     "true");
                    System.setProperty("probsparql.simjoin.deduplicate", "true");
                    try {
                        TimingResult rDivJoin = measureWithPruning(ds, withTheta(qDivJoinTemplate, theta));
                        System.out.printf("     DIVJOIN (pruning=%.1f%%): %.2f ms  (%d results)%n",
                            rDivJoin.pruning != null ? rDivJoin.pruning.pruningRate() * 100 : 0.0,
                            rDivJoin.timeMs, rDivJoin.resultCount);

                        divJoinRows.add(new String[]{
                            str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                            fmt(rDivJoin.timeMs), str(rDivJoin.resultCount)});

                        if (rDivJoin.pruning != null) {
                            PruningStats ps = rDivJoin.pruning;
                            pruningRows.add(new String[]{
                                str(nPairs), fmt(unimodalFrac), sel, fmt(theta),
                                str(ps.totalPairs), str(ps.prunedByDim), str(ps.prunedByMean),
                                str(ps.prunedByVariance), str(ps.prunedByBounds),
                                str(ps.computedFullJSD), str(ps.resultCount),
                                fmt(ps.pruningRate())
                            });
                        }

                        checkResultConsistency(
                            rInEngineCheapFirst,
                            rInEngineJsdFirst,
                            rDivJoin,
                            nPairs, unimodalFrac, sel);

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
        writeCsv(outputDir + "/exp2_calibration.csv", calibRows);
        writeCsv(outputDir + "/exp2_inengine_cheapfirst.csv", inEngineCheapFirstRows);
        writeCsv(outputDir + "/exp2_inengine_jsdfirst.csv", inEngineJsdFirstRows);
        writeCsv(outputDir + "/exp2_similarityjoin.csv", divJoinRows);
        writeCsv(outputDir + "/exp2_pruning_stats.csv", pruningRows);

        System.out.println("Results written to: " + outputDir);
    }

    // -----------------------------------------------------------------------
    // Calibration
    // -----------------------------------------------------------------------

    /**
     * Run the multimodal-only calibration query and return θ at the 10th, 50th, 90th percentiles.
     * Only considers multimodal-multimodal pairs, matching the retained variants.
     */
    private static double[] calibrate(Dataset ds, String sparql) {
        Query q = QueryFactory.create(sparql);
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
     * Time DIVJOIN with pruning enabled; also collect pruning stats.
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

    private static void checkResultConsistency(
            TimingResult rInEngineCheapFirst,
            TimingResult rInEngineJsdFirst,
            TimingResult rDivJoin,
            int nPairs, double unimodalFrac, String sel) {
        int inCf = rInEngineCheapFirst.resultCount;
        int inJf = rInEngineJsdFirst.resultCount;
        int dj   = rDivJoin.resultCount;

        if (!(inCf == inJf && inCf == dj)) {
            System.out.printf(
                "  [WARN] Result mismatch at nPairs=%d uf=%.1f sel=%s :: InEngine_CF=%d InEngine_JF=%d DIVJOIN=%d%n",
                nPairs, unimodalFrac, sel, inCf, inJf, dj);
        }
    }

    // -----------------------------------------------------------------------
    // Dataset loading
    // -----------------------------------------------------------------------

    private static Dataset loadDataset(String dataDir, int nPairs, double unimodalFrac) {
        String ufLabel = String.format(Locale.ROOT, "%.1f", unimodalFrac).replace('.', 'p');
        File ttlFile = new File(dataDir, "exp2_npairs_" + nPairs + "_uf_" + ufLabel + ".ttl");
        if (!ttlFile.exists()) {
            throw new IllegalArgumentException(
                "Missing dataset file: " + ttlFile + " (run generate_exp2.py first)");
        }
        Dataset ds = DatasetFactory.createTxnMem();
        RDFDataMgr.read(ds.getDefaultModel(), ttlFile.getAbsolutePath(), Lang.TTL);
        System.out.printf("  Dataset: loaded %s%n", ttlFile.getName());
        return ds;
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

    private static int[] parseIntList(String spec) {
        String[] parts = spec.split(",");
        int[] vals = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vals[i] = Integer.parseInt(parts[i].trim());
        }
        return vals;
    }

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
