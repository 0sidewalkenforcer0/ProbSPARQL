package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

/**
 * Exp 4.3 — Cross-type JSD over remote Fuseki endpoints.
 *
 * <p>The remote datasets contain cross-type distribution pairs. The measured
 * value is computed by {@code prob:jsd(?dA, ?dB)} on the Fuseki server. If the
 * dataset also contains {@code cfm:refSameTypeJSD}, this runner reports
 * absolute error against that reference; otherwise reference/error fields are
 * written as {@code NaN}.</p>
 */
public class Exp4CrossTypeJSD {

    private static final String Q_CROSS = """
        PREFIX cfm:  <http://example.org/ontology/cfm#>
        PREFIX prob: <http://probsparql.org/function#>
        SELECT ?pair ?idx ?ref ?jsd WHERE {
            ?pair cfm:hasDistA ?dA ;
                  cfm:hasDistB ?dB ;
                  cfm:pairIndex ?idx .
            OPTIONAL { ?pair cfm:refSameTypeJSD ?ref . }
            BIND(prob:jsd(?dA, ?dB) AS ?jsd)
        }
        ORDER BY ?idx""";

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4";
        String endpointTemplate = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--demo" -> {
                    // Demo mode is intentionally ignored for remote datasets.
                }
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.3: Remote Cross-Type JSD ===");
        System.out.printf("Endpoint template: %s%n", endpointTemplate);

        QueryFactory.create(Q_CROSS);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"PairType", "PairIdx", "SameTypeJSD", "CrossTypeJSD",
                "AbsError", "TimeSameNs", "TimeCrossNs"});

        runDataset("GMM<->Hist", "exp4_crosstype_gmm_hist", endpointTemplate, rows);
        runDataset("Dir<->Hist", "exp4_crosstype_dir_hist", endpointTemplate, rows);

        writeCsv(outputDir + "/exp4_crosstype.csv", rows);
        System.out.println("\nResults -> " + outputDir + "/exp4_crosstype.csv");
    }

    private static void runDataset(String pairType, String dataset, String endpointTemplate,
                                   List<String[]> rows) {
        String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, dataset);
        System.out.printf("%n-- %s via %s%n", pairType, endpoint);

        List<PairRow> collected = new ArrayList<>();

        long queryStart = System.nanoTime();
        RemoteBenchmarkClient.forEachSolution(endpoint, Q_CROSS, sol -> {
            double jsd = sol.getLiteral("jsd").getDouble();
            int idx = sol.getLiteral("idx").getInt();
            double ref = Double.NaN;
            if (sol.contains("ref") && sol.get("ref").isLiteral()) {
                ref = sol.getLiteral("ref").getDouble();
            }
            collected.add(new PairRow(idx, ref, jsd));
        });
        long totalNs = System.nanoTime() - queryStart;
        long perPairNs = collected.isEmpty() ? 0 : totalNs / collected.size();

        double sumAbsErr = 0.0;
        int errCount = 0;
        for (PairRow row : collected) {
            double err = Double.isNaN(row.ref()) ? Double.NaN : Math.abs(row.jsd() - row.ref());
            if (!Double.isNaN(err)) {
                sumAbsErr += err;
                errCount++;
            }
            rows.add(new String[]{pairType, String.valueOf(row.idx()), fmt(row.ref()), fmt(row.jsd()),
                    fmt(err), "0", String.valueOf(perPairNs)});
        }

        if (errCount > 0) {
            System.out.printf("  %d pairs, MAE=%.6f%n", collected.size(), sumAbsErr / errCount);
        } else {
            System.out.printf("  %d pairs, reference unavailable%n", collected.size());
        }
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
    }

    private record PairRow(int idx, double ref, double jsd) {}
}
