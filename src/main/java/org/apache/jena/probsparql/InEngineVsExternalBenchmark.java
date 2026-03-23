package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exp 2: In-Engine vs External JSD Computation
 *
 * <p>Compares two execution strategies for similarity-join queries:
 * <ul>
 *   <li><b>IN-ENGINE</b>  – single SPARQL query using {@code prob:jsd}
 *       inside a {@code FILTER} computed entirely inside the Jena ARQ engine</li>
 *   <li><b>EXTERNAL</b>  – bare BGP retrieves all GMM pairs, then the caller
 *       computes JSD in a follow-on step (timing reported separately;
 *       external compute is handled by {@code exp2_external_baseline.py})</li>
 * </ul>
 *
 * <p>Experiment sweeps pair counts: {@code N_PAIRS = {100, 500, 1000, 5000, 10000}}.
 * Selectivity is varied by adjusting the JSD threshold θ ∈ {0.1, 0.3, 0.5}.
 *
 * <p>Protocol: {@value #WARMUP_RUNS} warm-up runs then {@value #BENCHMARK_RUNS}
 * timed runs.  Median (ms) is reported.
 *
 * <p>Output CSVs (written to {@code --output-dir}):
 * <pre>
 *   exp2_inengine.csv ── NPairs,Theta,Approach,Median_ms,ResultCount
 *   exp2_pairs.json   ── exported pairs for external baseline script
 * </pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.InEngineVsExternalBenchmark"
 *   Optional: --output-dir benchmark/results
 */
public class InEngineVsExternalBenchmark {

    private static final int  WARMUP_RUNS    = 5;
    private static final int  BENCHMARK_RUNS = 30;

    private static final int[] N_PAIRS = {100, 500, 1000, 5000, 10_000};
    private static final double[] THRESHOLDS = {0.1, 0.3, 0.5};

    private static final String NS_EX   = "http://example.org/data/";
    private static final String NS_UQ   = "http://example.org/ontology/uncertainty#";
    private static final String NS_PROB = "http://probsparql.org/function#";

    // -----------------------------------------------------------------------
    // Queries
    // -----------------------------------------------------------------------

    /** IN-ENGINE: filter inside SPARQL, all work done within Jena. */
    private static String qInEngine(double theta) {
        return String.format("""
            PREFIX uq:   <http://example.org/ontology/uncertainty#>
            PREFIX prob: <http://probsparql.org/function#>
            SELECT ?rv1 ?rv2 ?jsd WHERE {
                ?rv1 uq:hasDistribution ?d1 .
                ?rv2 uq:hasDistribution ?d2 .
                FILTER(str(?rv1) < str(?rv2))
                BIND(prob:jsdivergence(?d1, ?d2) AS ?jsd)
                FILTER(?jsd <= %.2f)
            }""", theta);
    }

    /** EXTERNAL (fetch): BGP only — retrieve literals for external compute. */
    private static final String Q_FETCH = """
        PREFIX uq: <http://example.org/ontology/uncertainty#>
        SELECT ?rv1 ?rv2 ?d1 ?d2 WHERE {
            ?rv1 uq:hasDistribution ?d1 .
            ?rv2 uq:hasDistribution ?d2 .
            FILTER(str(?rv1) < str(?rv2))
        }""";

    // -----------------------------------------------------------------------
    // main
    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output-dir")) outputDir = args[++i];
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 2: In-Engine vs External JSD ===");
        System.out.printf("Warmup=%d  Runs=%d%n%n", WARMUP_RUNS, BENCHMARK_RUNS);

        List<String[]> inEngineRows = new ArrayList<>();
        inEngineRows.add(new String[]{"NPairs", "Theta", "Approach", "Median_ms", "ResultCount"});

        // Maps n → exported JSON pairs (built once per n, reused across θ values)
        Map<Integer, List<String[]>> pairCache = new LinkedHashMap<>();

        for (int nPairs : N_PAIRS) {
            // Number of unique pairs from an n-entity dataset: nPairs ≈ n*(n-1)/2
            // Solve for n: n ≈ (1 + sqrt(1 + 8*nPairs)) / 2
            int n = (int) Math.ceil((1 + Math.sqrt(1 + 8.0 * nPairs)) / 2.0);

            System.out.printf("── nPairs≈%d  (n=%d entities) ───────────────────%n", nPairs, n);
            Dataset ds = buildDataset(n);

            // Export pairs for Python external baseline (done once per n)
            List<String[]> pairs = exportPairs(ds);
            pairCache.put(nPairs, pairs);

            for (double theta : THRESHOLDS) {
                // IN-ENGINE timing
                double inEngMs = measureMedian(ds, qInEngine(theta));
                int    cnt     = countResults(ds, qInEngine(theta));

                System.out.printf("  θ=%.1f  IN-ENGINE  %.2f ms  (%d results)%n",
                                  theta, inEngMs, cnt);

                // EXTERNAL = fetch time only (the actual compute timing is in Python script)
                double fetchMs = measureMedian(ds, Q_FETCH);
                System.out.printf("  θ=%.1f  FETCH-ONLY %.2f ms%n", theta, fetchMs);

                inEngineRows.add(new String[]{
                    String.valueOf(nPairs), fmt(theta), "IN_ENGINE",
                    fmt(inEngMs), String.valueOf(cnt)});
                inEngineRows.add(new String[]{
                    String.valueOf(nPairs), fmt(theta), "FETCH_ONLY",
                    fmt(fetchMs), String.valueOf(pairs.size())});
            }
            ds.close();
        }

        // Write in-engine results CSV
        writeCsv(outputDir + "/exp2_inengine.csv", inEngineRows);

        // Write exported pairs JSON for Python baseline
        writePairsJson(outputDir + "/exp2_pairs.json", pairCache);

        System.out.println("\nResults written to " + outputDir);
    }

    // -----------------------------------------------------------------------
    // Timing
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

    private static int exec(Dataset ds, Query q) {
        int c = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); c++; }
        }
        return c;
    }

    private static int countResults(Dataset ds, String sparql) {
        return exec(ds, QueryFactory.create(sparql));
    }

    // -----------------------------------------------------------------------
    // Export pairs for external baseline
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

    private static void writePairsJson(String path, Map<Integer, List<String[]>> cache)
            throws IOException {
        // Write minimal JSON: { "100": [{rv1,rv2,d1,d2}, ...], "500": [...], ... }
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            pw.println("{");
            boolean firstN = true;
            for (Map.Entry<Integer, List<String[]>> e : cache.entrySet()) {
                if (!firstN) pw.println(",");
                firstN = false;
                pw.printf("  \"%d\": [%n", e.getKey());
                boolean firstP = true;
                for (String[] p : e.getValue()) {
                    if (!firstP) pw.println(",");
                    firstP = false;
                    // Escape backslashes and quotes inside GMM JSON strings
                    pw.printf("    {\"rv1\":\"%s\",\"rv2\":\"%s\","
                            + "\"d1\":%s,\"d2\":%s}",
                            p[0], p[1],
                            p[2],  // already valid JSON
                            p[3]);
                }
                pw.print("\n  ]");
            }
            pw.println("\n}");
        }
    }

    // -----------------------------------------------------------------------
    // Dataset builder (same structure as ScalabilityBenchmark)
    // -----------------------------------------------------------------------

    private static Dataset buildDataset(int n) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist = m.createProperty(NS_UQ + "hasDistribution");
        Resource rvType  = m.createResource(NS_UQ + "RandomVariable");
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 1; i <= n; i++) {
            Resource rv = m.createResource(NS_EX + "rv2_" + i);
            rv.addProperty(RDF.type, rvType);
            rv.addProperty(hasDist, m.createTypedLiteral(makeGmmJson(2, rng),
                                                          NS_UQ + "gmmLiteral"));
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
    // CSV / JSON helpers
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
