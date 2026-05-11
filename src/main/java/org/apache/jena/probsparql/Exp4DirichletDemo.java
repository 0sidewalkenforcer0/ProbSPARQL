package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.QuerySolution;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Exp 4.5 — Dirichlet distribution demonstration over remote Fuseki.
 *
 * <p>The dataset must be preloaded as service {@code exp4_dirichlet}.</p>
 */
public class Exp4DirichletDemo {

    private static final String Q_DIR_1 = """
        PREFIX cfm:  <http://example.org/ontology/cfm#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?component ?divergence WHERE {
            ?component cfm:hasMeasuredComposition ?measured ;
                       cfm:hasExpectedComposition ?expected .
            BIND(prob:jsd(?measured, ?expected) AS ?divergence)
            FILTER(?divergence > 0.05)
        }
        ORDER BY DESC(?divergence)
        LIMIT 10""";

    private static final String Q_DIR_2 = """
        PREFIX cfm:  <http://example.org/ontology/cfm#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?component ?meanComposition WHERE {
            ?component cfm:hasMeasuredComposition ?dist .
            BIND(prob:mean(?dist) AS ?meanComposition)
        }
        LIMIT 10""";

    private static final String Q_DIR_3 = """
        PREFIX cfm:  <http://example.org/ontology/cfm#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?component ?prob WHERE {
            ?component cfm:hasMeasuredComposition ?dist .
            BIND(prob:cdf(?dist, 0.4) AS ?prob)
            FILTER(?prob > 0.5)
        }
        LIMIT 10""";

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

        String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "exp4_dirichlet");
        System.out.println("=== Exp 4.5: Remote Dirichlet Distribution Demonstration ===");
        System.out.printf("Endpoint: %s%n%n", endpoint);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Query", "Source", "ResultCount", "SampleResult", "Status"});

        runQuery("Q-dir-1 (anomaly JSD)", endpoint, Q_DIR_1, rows);
        runQuery("Q-dir-2 (mean vector)", endpoint, Q_DIR_2, rows);
        runQuery("Q-dir-3 (CDF filter)", endpoint, Q_DIR_3, rows);

        writeCsv(outputDir + "/exp4_dirichlet_demo.csv", rows);
        System.out.println("\nResults -> " + outputDir + "/exp4_dirichlet_demo.csv");
    }

    private static void runQuery(String label, String endpoint, String sparql,
                                 List<String[]> rows) {
        QueryFactory.create(sparql);
        String status = "OK";
        int[] count = {0};
        String[] sample = {""};
        try {
            RemoteBenchmarkClient.forEachSolution(endpoint, sparql, row -> {
                count[0]++;
                if (count[0] == 1) {
                    sample[0] = sample(row);
                }
            });
        } catch (Exception e) {
            status = "ERROR: " + e.getMessage();
        }
        System.out.printf("  %-30s n=%3d %s%n", label, count[0], status);
        if (!sample[0].isEmpty()) {
            System.out.printf("    sample: %s%n", sample[0]);
        }
        rows.add(new String[]{label, "remote", String.valueOf(count[0]), sample[0], status});
    }

    private static String sample(QuerySolution row) {
        List<String> pieces = new ArrayList<>();
        row.varNames().forEachRemaining(var -> {
            if (row.get(var) != null && pieces.size() < 2) {
                pieces.add(var + "=" + row.get(var));
            }
        });
        return String.join(" | ", pieces);
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", Arrays.stream(row)
                        .map(s -> "\"" + s.replace("\"", "'") + "\"")
                        .toList()));
            }
        }
    }
}
