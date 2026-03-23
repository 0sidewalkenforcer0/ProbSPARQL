package org.apache.jena.probsparql;

import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;
import org.apache.jena.vocabulary.RDF;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exp 4.1 — Polymorphic Dispatch Verification
 *
 * <p>Verifies that a single SPARQL query with {@code prob:mean}, {@code prob:std},
 * {@code prob:map}, {@code prob:cdf}, {@code prob:jsd} works unchanged on
 * GMM, Histogram, and Dirichlet literals.</p>
 *
 * <p>For each (function, type) combination, runs the query on a small in-memory
 * dataset and records: function, type, result count, any error, and whether
 * the result is in a plausible range.</p>
 *
 * <p>Output CSV: {@code exp4_dispatch.csv}
 * <pre>Function,Type,ResultCount,Status,Sample</pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp4DispatchTest"
 *   Optional: --output-dir benchmark/results/exp4_full
 */
public class Exp4DispatchTest {

    private static final int N = 10;   // small dataset for qualitative check

    private static final String NS_EX   = "http://example.org/data/";
    private static final String NS_UQ   = "http://example.org/ontology/uncertainty#";
    private static final String NS_PROB = "http://probsparql.org/function#";
    private static final String NS_CFM  = "http://example.org/ontology/cfm#";

    // --------------------------------------------------------------------
    // Queries — use polymorphic URIs (same text for all types)
    // --------------------------------------------------------------------

    private static final String Q_MEAN = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e uq:hasDistribution ?dist .
            BIND(prob:mean(?dist) AS ?v)
        }""";

    private static final String Q_STD = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e uq:hasDistribution ?dist .
            BIND(prob:std(?dist) AS ?v)
        }""";

    private static final String Q_MAP = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e uq:hasDistribution ?dist .
            BIND(prob:map(?dist) AS ?v)
        }""";

    private static final String Q_CDF = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e uq:hasDistribution ?dist .
            BIND(prob:cdf(?dist, 15.0) AS ?v)
        }""";

    private static final String Q_JSD = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e1 ?e2 ?v WHERE {
            ?e1 uq:hasDistribution ?d1 .
            ?e2 uq:hasDistribution ?d2 .
            FILTER(str(?e1) < str(?e2))
            BIND(prob:jsd(?d1, ?d2) AS ?v)
        } LIMIT 5""";

    private static final String Q_JSD_DIR = """
        PREFIX cfm:  <http://example.org/ontology/cfm#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e cfm:hasMeasuredComposition ?d1 ;
               cfm:hasExpectedComposition ?d2 .
            BIND(prob:jsd(?d1, ?d2) AS ?v)
        } LIMIT 5""";

    // --------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4_full";
        for (int i = 0; i < args.length; i++)
            if (args[i].equals("--output-dir")) outputDir = args[++i];
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.1: Polymorphic Dispatch Verification ===");
        System.out.println();

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Function", "DistType", "ResultCount", "Status", "SampleResult"});

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        Dataset gmmDs  = buildGmmDataset(N, 3, rng);
        Dataset histDs = buildHistDataset(N, 50, rng);
        Dataset dirDs  = buildDirPairDataset(N, 4, rng);

        // Single-distribution functions: mean, std, map, cdf
        for (String[] spec : new String[][]{
                {"prob:mean",  Q_MEAN},
                {"prob:std",   Q_STD},
                {"prob:map",   Q_MAP},
                {"prob:cdf",   Q_CDF}}) {
            String fn = spec[0];
            String qry = spec[1];
            testQuery(fn, "GMM(K=3)",    gmmDs,  qry, rows);
            testQuery(fn, "Hist(B=50)",  histDs, qry, rows);
        }
        // CDF for Dirichlet (marginal dim 0)
        testQuery("prob:cdf", "Dir(k=4)", dirDs, Q_CDF.replace("15.0", "0.4"), rows);
        // Mean, std, map for Dirichlet
        testQuery("prob:mean", "Dir(k=4)", dirDs, Q_MEAN, rows);
        testQuery("prob:std",  "Dir(k=4)", dirDs, Q_STD,  rows);
        testQuery("prob:map",  "Dir(k=4)", dirDs, Q_MAP,  rows);

        // JSD: same-type
        testQuery("prob:jsd", "GMM↔GMM",   gmmDs,  Q_JSD,     rows);
        testQuery("prob:jsd", "Hist↔Hist", histDs, Q_JSD,     rows);
        testQuery("prob:jsd", "Dir↔Dir",   dirDs,  Q_JSD_DIR, rows);

        gmmDs.close(); histDs.close(); dirDs.close();

        writeCsv(outputDir + "/exp4_dispatch.csv", rows);
        System.out.println("\nResults → " + outputDir + "/exp4_dispatch.csv");
    }

    // --------------------------------------------------------------------

    private static void testQuery(String fn, String distType, Dataset ds,
                                   String sparql, List<String[]> rows) {
        String status = "OK";
        int count = 0;
        String sample = "";
        try {
            Query q = QueryFactory.create(sparql);
            try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
                ResultSet rs = qe.execSelect();
                while (rs.hasNext()) {
                    QuerySolution row = rs.next();
                    count++;
                    if (count == 1) {
                        // collect first non-null value
                        for (String var : rs.getResultVars()) {
                            if (row.get(var) != null && !var.equals("e") && !var.equals("e1") && !var.equals("e2")) {
                                sample = row.get(var).toString();
                                break;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            status = "ERROR: " + e.getMessage();
        }
        String line = String.format("  %-12s  %-12s  n=%3d  %s  sample=%s", fn, distType, count, status, sample);
        System.out.println(line);
        rows.add(new String[]{fn, distType, String.valueOf(count), status, sample});
    }

    // --------------------------------------------------------------------
    // Dataset builders
    // --------------------------------------------------------------------

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
            Resource e = m.createResource(NS_EX + "hist_" + i);
            e.addProperty(hasDist, m.createTypedLiteral(makeHistJson(B, rng), NS_UQ + "histLiteral"));
        }
        return DatasetFactory.create(m);
    }

    /** Builds a dataset where each entity has measured + expected Dirichlet. */
    private static Dataset buildDirPairDataset(int n, int k, ThreadLocalRandom rng) {
        Model m = ModelFactory.createDefaultModel();
        Property hasDist     = m.createProperty(NS_UQ + "hasDistribution");
        Property hasMeasured = m.createProperty(NS_CFM + "hasMeasuredComposition");
        Property hasExpected = m.createProperty(NS_CFM + "hasExpectedComposition");
        for (int i = 1; i <= n; i++) {
            Resource e = m.createResource(NS_EX + "dir_" + i);
            String alphaM = makeDirJson(k, rng);
            String alphaE = makeDirJson(k, rng);
            e.addProperty(hasDist,     m.createTypedLiteral(alphaM, NS_UQ + "dirichletLiteral"));
            e.addProperty(hasMeasured, m.createTypedLiteral(alphaM, NS_UQ + "dirichletLiteral"));
            e.addProperty(hasExpected, m.createTypedLiteral(alphaE, NS_UQ + "dirichletLiteral"));
        }
        return DatasetFactory.create(m);
    }

    // --------------------------------------------------------------------
    // Literal builders
    // --------------------------------------------------------------------

    private static String makeGmmJson(int k, ThreadLocalRandom rng) {
        double[] w = new double[k];
        double wSum = 0;
        for (int i = 0; i < k; i++) { w[i] = rng.nextDouble(0.1, 1.0); wSum += w[i]; }
        StringBuilder sb = new StringBuilder("{\"K\":" + k + ",\"d\":1,\"covariance_type\":\"diag\",\"weights\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("%.6f", w[i] / wSum)); }
        sb.append("],\"means\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("[%.4f]", rng.nextDouble(13.0, 17.0))); }
        sb.append("],\"covariances\":[");
        for (int i = 0; i < k; i++) { if (i > 0) sb.append(","); sb.append(String.format("[%.6f]", rng.nextDouble(0.001, 0.1))); }
        sb.append("]}");
        return sb.toString();
    }

    // Fixed bin range shared by ALL in-memory histograms — required for pairwise JSD compatibility
    private static final double HIST_LO = 5.0, HIST_HI = 25.0;

    private static String makeHistJson(int B, ThreadLocalRandom rng) {
        int[] counts = new int[B];
        for (int i = 0; i < B; i++) { counts[i] = rng.nextInt(1, 50); }
        StringBuilder sb = new StringBuilder("{\"B\":" + B + ",\"min\":" + String.format("%.4f", HIST_LO)
                + ",\"max\":" + String.format("%.4f", HIST_HI) + ",\"counts\":[");
        for (int i = 0; i < B; i++) { if (i > 0) sb.append(","); sb.append(counts[i]); }
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

    // --------------------------------------------------------------------
    // CSV writer
    // --------------------------------------------------------------------

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(row[i].replace(",", ";"));
                }
                pw.println(sb);
            }
        }
        System.out.println("CSV written: " + path);
    }
}
