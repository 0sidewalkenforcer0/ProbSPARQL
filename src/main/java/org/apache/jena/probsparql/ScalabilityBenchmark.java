package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Exp 1: System Overhead — ProbSPARQL vs Deterministic SPARQL
 *
 * <p>Loads pre-generated TTL datasets and SPARQL query files from disk, executes
 * them in-process using the standard Jena {@code QueryExecutionFactory}, and
 * records per-run wall-clock times. No HTTP / Fuseki overhead is included.
 *
 * <h3>Dataset naming convention</h3>
 * <pre>
 *   benchmark/data/exp1/component/exp1_{scale}_K{k}.ttl   — probabilistic (uq:gmmLiteral)
 *   benchmark/data/exp1/component/exp1_{scale}_det.ttl     — deterministic (xsd:double)
 * </pre>
 *
 * <h3>Query files</h3>
 * <pre>
 *   benchmark/queries/exp1/component/det/q1.sparql  q2.sparql  q3.sparql
 *   benchmark/queries/exp1/component/prob/q1.sparql q2.sparql  q3.sparql  q4.sparql
 * </pre>
 *
 * <h3>Output CSVs</h3>
 * <pre>
 *   benchmark/results/exp1/component/exp1_raw.csv     — Scale,K,QueryID,Type,Run,Time_ms
 *   benchmark/results/exp1/component/exp1_summary.csv — Scale,K,QueryID,Type,Median_ms,IQR_ms,OverheadRatio
 * </pre>
 *
 * <h3>Usage</h3>
 * <pre>
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.ScalabilityBenchmark"
 *   # Optional flags:
 *   #   --data-dir   benchmark/data/exp1/component
 *   #   --query-dir  benchmark/queries/exp1/component
 *   #   --output-dir benchmark/results/exp1/component
 *   #   --scales     E1 E3 E5 E7
 *   #   --k-values   1 3 5 10
 * </pre>
 */
public class ScalabilityBenchmark {

    private static int WARMUP_RUNS    = 3;
    private static int BENCHMARK_RUNS = 10;

    private static final String[] DEFAULT_SCALES   = {"E1", "E3", "E5", "E7"};
    private static final int[]    DEFAULT_K_VALUES  = {1, 3, 5, 10};

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        // Defaults
        String dataDir   = "benchmark/data/exp1/component";
        String queryDir  = "benchmark/queries/exp1/component";
        String outputDir = "benchmark/results/exp1/component";
        List<String>  scales  = new ArrayList<>(Arrays.asList(DEFAULT_SCALES));
        List<Integer> kValues = new ArrayList<>();
        for (int k : DEFAULT_K_VALUES) kValues.add(k);

        // Parse CLI flags
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir"   -> dataDir   = args[++i];
                case "--query-dir"  -> queryDir  = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--scales"  -> {
                    scales.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) scales.add(args[++i]);
                }
                case "--k-values" -> {
                    kValues.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) kValues.add(Integer.parseInt(args[++i]));
                }
                case "--warmup" -> WARMUP_RUNS    = Integer.parseInt(args[++i]);
                case "--runs"   -> BENCHMARK_RUNS = Integer.parseInt(args[++i]);
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp 1: System Overhead (DET vs PROB) ===");
        System.out.printf("Data dir  : %s%n", dataDir);
        System.out.printf("Query dir : %s%n", queryDir);
        System.out.printf("Warmup    : %d   Benchmark: %d runs%n", WARMUP_RUNS, BENCHMARK_RUNS);
        System.out.printf("Scales    : %s%n", scales);
        System.out.printf("K values  : %s%n", kValues);
        System.out.println();

        // Accumulate rows for both output files
        List<String[]> rawRows = new ArrayList<>();
        List<String[]> sumRows = new ArrayList<>();

        // DET medians per (scale, queryId) used to compute overhead ratios
        Map<String, Double> detMedians = new HashMap<>();

        String[] detQueryIds  = {"Q1", "Q2", "Q3"};
        String[] probQueryIds = {"Q1", "Q2", "Q3", "Q4"};

        for (String scale : scales) {
            System.out.printf("─── Scale: %s ───────────────────────────────────────%n", scale);

            // ---- DET dataset (once per scale) ----
            String detTtl = dataDir + "/exp1_" + scale + "_det.ttl";
            System.out.printf("  Loading DET : %s%n", detTtl);
            Model   detModel = RDFDataMgr.loadModel(detTtl);
            Dataset detDs    = DatasetFactory.create(detModel);

            for (String qid : detQueryIds) {
                String sparqlPath = queryDir + "/det/" + qid.toLowerCase() + ".sparql";
                Query  query      = loadQuery(sparqlPath);
                long[] times      = runTimed(detDs, query);
                double medMs      = median(times);
                double iqrMs      = iqr(times);

                detMedians.put(scale + "::" + qid, medMs);

                for (int r = 0; r < BENCHMARK_RUNS; r++) {
                    rawRows.add(row(scale, "DET", qid, "DET", r + 1, times[r] / 1e6));
                }
                sumRows.add(sumRow(scale, "DET", qid, "DET", medMs, iqrMs, "1.00"));
                System.out.printf("    %-2s DET  : median=%7.3f ms%n", qid, medMs);
            }
            detDs.close();
            detModel.close();

            // ---- PROB datasets (once per K) ----
            for (int k : kValues) {
                String probTtl = dataDir + "/exp1_" + scale + "_K" + k + ".ttl";
                System.out.printf("  Loading PROB K=%d : %s%n", k, probTtl);
                Model   probModel = RDFDataMgr.loadModel(probTtl);
                Dataset probDs    = DatasetFactory.create(probModel);

                for (String qid : probQueryIds) {
                    String sparqlPath = queryDir + "/prob/" + qid.toLowerCase() + ".sparql";
                    Query  query      = loadQuery(sparqlPath);
                    long[] times      = runTimed(probDs, query);
                    double medMs      = median(times);
                    double iqrMs      = iqr(times);

                    String ratioStr;
                    if ("Q4".equals(qid)) {
                        ratioStr = "—";   // No deterministic equivalent
                    } else {
                        Double detMed = detMedians.get(scale + "::" + qid);
                        ratioStr = (detMed != null && detMed > 0)
                                   ? fmt4(medMs / detMed) : "NaN";
                    }

                    for (int r = 0; r < BENCHMARK_RUNS; r++) {
                        rawRows.add(row(scale, String.valueOf(k), qid, "PROB", r + 1, times[r] / 1e6));
                    }
                    sumRows.add(sumRow(scale, String.valueOf(k), qid, "PROB", medMs, iqrMs, ratioStr));
                    System.out.printf("    %-2s K=%-2d PROB: median=%7.3f ms  ratio=%s%n",
                                      qid, k, medMs, ratioStr);
                }
                probDs.close();
                probModel.close();
            }
            System.out.println();
        }

        // ---- Write output ----
        String rawPath = outputDir + "/exp1_raw.csv";
        String sumPath = outputDir + "/exp1_summary.csv";

        try (PrintWriter pw = new PrintWriter(new FileWriter(rawPath))) {
            pw.println("Scale,K,QueryID,Type,Run,Time_ms");
            for (String[] r : rawRows) pw.println(String.join(",", r));
        }
        try (PrintWriter pw = new PrintWriter(new FileWriter(sumPath))) {
            pw.println("Scale,K,QueryID,Type,Median_ms,IQR_ms,OverheadRatio");
            for (String[] r : sumRows) pw.println(String.join(",", r));
        }

        System.out.printf("Results written to:%n  %s%n  %s%n", rawPath, sumPath);
    }

    // -----------------------------------------------------------------------
    // Query loading
    // -----------------------------------------------------------------------

    private static Query loadQuery(String path) throws IOException {
        String sparql = Files.readString(Path.of(path));
        return QueryFactory.create(sparql);
    }

    // -----------------------------------------------------------------------
    // Timing
    // -----------------------------------------------------------------------

    private static long[] runTimed(Dataset ds, Query q) {
        for (int i = 0; i < WARMUP_RUNS; i++) drain(ds, q);
        long[] times = new long[BENCHMARK_RUNS];
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            drain(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        return times;
    }

    private static void drain(Dataset ds, Query q) {
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) rs.next();
        }
    }

    // -----------------------------------------------------------------------
    // Statistics — operate on nanosecond arrays
    // -----------------------------------------------------------------------

    private static double median(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        return s[s.length / 2] / 1_000_000.0;
    }

    private static double iqr(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        double q1 = s[Math.max(0, s.length / 4)]                         / 1_000_000.0;
        double q3 = s[Math.min(s.length - 1, 3 * s.length / 4)]          / 1_000_000.0;
        return q3 - q1;
    }

    // -----------------------------------------------------------------------
    // Row builders
    // -----------------------------------------------------------------------

    private static String[] row(String scale, String k, String qid,
                                 String type, int run, double timeMs) {
        return new String[]{scale, k, qid, type, String.valueOf(run), fmt4(timeMs)};
    }

    private static String[] sumRow(String scale, String k, String qid, String type,
                                    double medMs, double iqrMs, String ratio) {
        return new String[]{scale, k, qid, type, fmt4(medMs), fmt4(iqrMs), ratio};
    }

    private static String fmt4(double v) {
        return Double.isNaN(v) ? "NaN" : String.format("%.4f", v);
    }
}
