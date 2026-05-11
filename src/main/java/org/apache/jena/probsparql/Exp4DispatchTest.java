package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Exp 4.1 — Polymorphic dispatch verification over remote Fuseki endpoints.
 *
 * <p>Each dataset must already be loaded on the remote server. The benchmark
 * verifies that the same polymorphic SPARQL functions execute for GMM,
 * Histogram, and Dirichlet literals.</p>
 */
public class Exp4DispatchTest {

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

    private static final String Q_CDF_DIR = """
        PREFIX uq:   <http://example.org/ontology/uncertainty#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?e ?v WHERE {
            ?e uq:hasDistribution ?dist .
            BIND(prob:cdf(?dist, 0.4) AS ?v)
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

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4";
        String endpointTemplate = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.1: Polymorphic Dispatch Verification ===");
        System.out.printf("Endpoint template: %s%n%n", endpointTemplate);

        String gmmEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_dispatch_gmm_K3");
        String histEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_dispatch_hist_B50");
        String dirEndpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_dispatch_dir_k4");

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Function", "DistType", "ResultCount", "Status", "SampleResult"});

        for (String[] spec : new String[][]{
                {"prob:mean", Q_MEAN},
                {"prob:std", Q_STD},
                {"prob:map", Q_MAP},
                {"prob:cdf", Q_CDF}}) {
            testQuery(spec[0], "GMM(K=3)", gmmEndpoint, spec[1], rows);
            testQuery(spec[0], "Hist(B=50)", histEndpoint, spec[1], rows);
        }

        testQuery("prob:mean", "Dir(k=4)", dirEndpoint, Q_MEAN, rows);
        testQuery("prob:std", "Dir(k=4)", dirEndpoint, Q_STD, rows);
        testQuery("prob:map", "Dir(k=4)", dirEndpoint, Q_MAP, rows);
        testQuery("prob:cdf", "Dir(k=4)", dirEndpoint, Q_CDF_DIR, rows);

        testQuery("prob:jsd", "GMM<->GMM", gmmEndpoint, Q_JSD, rows);
        testQuery("prob:jsd", "Hist<->Hist", histEndpoint, Q_JSD, rows);
        testQuery("prob:jsd", "Dir<->Dir", dirEndpoint, Q_JSD_DIR, rows);

        writeCsv(outputDir + "/exp4_dispatch.csv", rows);
        System.out.println("\nResults -> " + outputDir + "/exp4_dispatch.csv");
    }

    private static void testQuery(String fn, String distType, String endpoint,
                                  String sparql, List<String[]> rows) {
        QueryFactory.create(sparql);
        String status = "OK";
        int[] count = {0};
        String[] sample = {""};
        try {
            RemoteBenchmarkClient.forEachSolution(endpoint, sparql, row -> {
                count[0]++;
                if (count[0] == 1) {
                    sample[0] = sampleValue(row);
                }
            });
        } catch (Exception e) {
            status = "ERROR: " + e.getMessage();
        }
        System.out.printf("  %-12s  %-12s  n=%3d  %s  sample=%s%n",
                fn, distType, count[0], status, sample[0]);
        rows.add(new String[]{fn, distType, String.valueOf(count[0]), status, sample[0]});
    }

    private static String sampleValue(QuerySolution row) {
        for (String var : List.of("v", "meanComposition", "prob", "divergence")) {
            if (row.contains(var)) {
                return row.get(var).toString();
            }
        }
        java.util.Iterator<String> vars = row.varNames();
        while (vars.hasNext()) {
            String var = vars.next();
            if (!var.equals("e") && !var.equals("e1") && !var.equals("e2") && row.get(var) != null) {
                return row.get(var).toString();
            }
        }
        return "";
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }
}
