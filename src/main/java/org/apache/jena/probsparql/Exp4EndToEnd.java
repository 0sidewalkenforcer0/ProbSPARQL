package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;

import java.io.*;
import java.util.*;

/**
 * Exp 4.4 — End-to-End Query Performance: GMM vs Histogram
 *
 * <p>Runs two Exp1-aligned probabilistic queries on three dataset types:
 * <ul>
 *   <li>GMM K=3 (from exp1 data files)</li>
 *   <li>Histogram B=50 (derived from same GMM)</li>
 *   <li>Histogram B=100 (derived from same GMM)</li>
 * </ul>
 * at scales E3 (100 gears), E5 (1000 gears), E7 (10000 gears).
 *
 * <p>The queries mirror the current Exp1 design:
 * <ul>
 *   <li>Q2: probabilistic filtering on CT tooth measurements via {@code prob:cdf}</li>
 *   <li>Q4: CT-vs-SL distribution comparison on the same tooth via {@code prob:jsdivergence}</li>
 * </ul>
 *
 * <p>Queries are loaded from {@code benchmark/queries/exp4/}.
 *
 * <p>Output CSV: {@code exp4_endtoend.csv}
 * <pre>Query,Scale,DistType,Param,N_entities,MedianMs,IQRMs</pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp4EndToEnd" \
 *       -Dexec.args="--data-dir benchmark/data"
 */
public class Exp4EndToEnd {

    private static int WARMUP_RUNS = 3;
    private static int TIMED_RUNS  = 10;
    private static boolean DEMO_MODE = false;
    private static final String QUERY_DIR = "benchmark/queries/exp4";

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4";
        String dataDir   = "benchmark/data";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output-dir")) outputDir = args[++i];
            if (args[i].equals("--data-dir"))   dataDir   = args[++i];
            if (args[i].equals("--demo"))        DEMO_MODE = true;
        }
        if (DEMO_MODE) {
            WARMUP_RUNS = 1; TIMED_RUNS = 1;
            System.out.println("  [DEMO MODE: warmup=1, runs=1]");
        }
        new File(outputDir).mkdirs();
        String q2Filter = readQuery(QUERY_DIR + "/q2_filter.sparql");
        String q4Jsd = readQuery(QUERY_DIR + "/q4_jsd.sparql");

        System.out.println("=== Exp 4.4: End-to-End Query Performance ===");
        System.out.printf("  Data: %s  warmup=%d  runs=%d  demo=%b%n%n", dataDir, WARMUP_RUNS, TIMED_RUNS, DEMO_MODE);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Query", "Scale", "DistType", "Param", "N_entities",
                               "MedianMs", "IQRMs"});

        // demo: only E3 (100 entities) to keep runtime under 1 min
        for (String scale : DEMO_MODE ? new String[]{"E3"} : new String[]{"E3", "E5", "E7"}) {
            System.out.println("── Scale " + scale + " ──────────────────────────────────────");

            // GMM dataset
            String gmmPath = dataDir + "/exp1/exp1_" + scale + "_K3.ttl";
            Dataset gmmDs = loadTtl(gmmPath);
            if (gmmDs != null) {
                int n = countEntities(gmmDs);
                System.out.printf("  GMM K=3  (N=%d)%n", n);
                run("Q2-Filter", scale, "GMM", "K=3", n, gmmDs, q2Filter, rows);
                run("Q4-JSD", scale, "GMM", "K=3", n, gmmDs, q4Jsd, rows);
                gmmDs.close();
            }

            // Histogram datasets
            for (int B : new int[]{50, 100}) {
                String histPath = dataDir + "/exp4/exp4_" + scale + "_hist_B" + B + ".ttl";
                Dataset histDs = loadTtl(histPath);
                if (histDs != null) {
                    int n = countEntities(histDs);
                    System.out.printf("  Hist B=%d  (N=%d)%n", B, n);
                    run("Q2-Filter", scale, "Hist", "B=" + B, n, histDs, q2Filter, rows);
                    run("Q4-JSD", scale, "Hist", "B=" + B, n, histDs, q4Jsd, rows);
                    histDs.close();
                } else {
                    System.out.printf("  SKIP Hist B=%d: %s not found%n", B, histPath);
                }
            }
        }

        writeCsv(outputDir + "/exp4_endtoend.csv", rows);
        System.out.println("Results → " + outputDir + "/exp4_endtoend.csv");
    }

    // -----------------------------------------------------------------------

    private static void run(String qid, String scale, String type, String param,
                             int n, Dataset ds, String sparql, List<String[]> rows) {
        Query q = QueryFactory.create(sparql);
        for (int i = 0; i < WARMUP_RUNS; i++) execCount(ds, q);

        long[] times = new long[TIMED_RUNS];
        for (int i = 0; i < TIMED_RUNS; i++) {
            long t0 = System.nanoTime();
            execCount(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        double medMs = times[TIMED_RUNS / 2] / 1_000_000.0;
        double iqrMs = (times[TIMED_RUNS * 3 / 4] - times[TIMED_RUNS / 4]) / 1_000_000.0;

        System.out.printf("    %-7s  %-5s  %-5s  median=%.1f ms  iqr=%.1f ms%n",
                qid, type, param, medMs, iqrMs);
        rows.add(new String[]{qid, scale, type, param, String.valueOf(n),
                fmt(medMs), fmt(iqrMs)});
    }

    private static int execCount(Dataset ds, Query q) {
        int n = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) { rs.next(); n++; }
        }
        return n;
    }

    private static Dataset loadTtl(String path) {
        File f = new File(path);
        if (!f.exists()) return null;
        try {
            Model m = ModelFactory.createDefaultModel();
            m.read(f.toURI().toString(), "TURTLE");
            return DatasetFactory.create(m);
        } catch (Exception e) {
            System.err.println("  ERROR loading " + path + ": " + e.getMessage());
            return null;
        }
    }

    private static int countEntities(Dataset ds) {
        String q = "SELECT (COUNT(DISTINCT ?e) AS ?n) WHERE { ?e ?p ?o }";
        try (QueryExecution qe = QueryExecutionFactory.create(QueryFactory.create(q), ds)) {
            ResultSet rs = qe.execSelect();
            if (rs.hasNext()) return rs.next().getLiteral("n").getInt();
        } catch (Exception ignored) {}
        return -1;
    }

    private static String fmt(double v) { return String.format("%.3f", v); }

    private static String readQuery(String path) throws IOException {
        return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
        System.out.println("CSV written: " + path);
    }
}
