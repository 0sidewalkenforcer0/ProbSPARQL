package org.apache.jena.probsparql;

import org.apache.jena.query.QueryFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exp 3 — remote sampling-method classification accuracy.
 *
 * <p>The benchmark sends mode-specific SPARQL queries to remote Fuseki services
 * and computes accuracy against reference JSD values embedded in the remote
 * Exp3 datasets as {@code prob:referenceJSD}.</p>
 */
public class Exp3Benchmark {

    private static final double THETA = 0.3;
    private static int warmup = 3;
    private static int repeat = 10;
    private static int expectedPairs = 2400;

    private static final String[] DATASETS = {"easy", "medium", "hard", "mixed"};
    private static final String GT_LABEL = "GT_CSV";
    private static final String[] METHODS = {
        "V1_MC",
        "V2_STRATIFIED",
        "V3_SPRT",
        "V4_BOUNDS",
        "V5_ADAPTIVE"
    };

    private static final String REF_QUERY = """
        PREFIX p: <http://example.org/prob#>
        SELECT ?idx ?trueJSD WHERE {
          ?left a p:LeftEntity ;
                p:pairIndex ?idx ;
                p:referenceJSD ?trueJSD ;
                p:hasGMM ?d1 .
          ?right a p:RightEntity ;
                 p:pairIndex ?idx ;
                 p:hasGMM ?d2 .
        }
        ORDER BY ?idx""";

    private static final String MODE_QUERY_TEMPLATE = """
        PREFIX p:  <http://example.org/prob#>
        PREFIX fn: <http://probsparql.org/function#>
        SELECT ?idx ?trueJSD ?estJSD WHERE {
          ?left a p:LeftEntity ;
                p:pairIndex ?idx ;
                p:referenceJSD ?trueJSD ;
                p:hasGMM ?d1 .
          ?right a p:RightEntity ;
                 p:pairIndex ?idx ;
                 p:hasGMM ?d2 .
          BIND(fn:jsdMode(?d1, ?d2, "__MODE__") AS ?estJSD)
        }
        ORDER BY ?idx""";

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp3";
        String endpointTemplate = null;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--endpoint-template" -> endpointTemplate = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--warmup" -> warmup = Integer.parseInt(args[++i]);
                case "--repeat" -> repeat = Integer.parseInt(args[++i]);
                case "--expected-pairs" -> expectedPairs = Integer.parseInt(args[++i]);
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }

        new File(outputDir).mkdirs();

        System.out.println("=== Exp 3: Remote Classification Accuracy ===");
        System.out.printf("Endpoint template: %s%n", endpointTemplate);
        System.out.printf("Threshold theta = %.1f%n", THETA);
        System.out.println("Methods     : " + Arrays.toString(METHODS));
        System.out.println("Datasets    : " + Arrays.toString(DATASETS));
        System.out.println("Warmup      : " + warmup);
        System.out.println("Runs        : " + repeat);
        System.out.println();

        List<String[]> aggRows = new ArrayList<>();
        List<String[]> pairRows = new ArrayList<>();
        aggRows.add(new String[]{"Method", "Dataset", "Accuracy", "Precision", "Recall", "F1",
                "MAE", "RMSE", "Latency_ms"});
        pairRows.add(new String[]{"Method", "Dataset", "PairIdx", "TrueJSD", "EstJSD_mean",
                "EstJSD_std", "TrueLabel", "PredLabel"});

        QueryFactory.create(REF_QUERY);

        for (String dataset : DATASETS) {
            String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, "simjoin_" + dataset);
            List<PairReference> refs = loadReferences(endpoint);
            int nPairs = refs.size();
            if (expectedPairs > 0 && nPairs != expectedPairs) {
                throw new IllegalStateException(
                    "Dataset " + dataset + " has " + nPairs + " pairs; expected " + expectedPairs);
            }
            System.out.printf("Dataset %-6s : %d aligned pairs%n", dataset, nPairs);

            double[] trueJSD = new double[nPairs];
            boolean[] trueLabels = new boolean[nPairs];
            for (int i = 0; i < nPairs; i++) {
                trueJSD[i] = refs.get(i).referenceJSD();
                trueLabels[i] = trueJSD[i] <= THETA;
            }

            double[] gtCls = classificationMetrics(trueLabels, trueLabels);
            aggRows.add(new String[]{GT_LABEL, dataset, fmt(gtCls[0]), fmt(gtCls[1]), fmt(gtCls[2]), fmt(gtCls[3]),
                    "0.000000", "0.000000", "0.000"});

            for (String method : METHODS) {
                System.out.printf("  Method %-15s: ", method);
                String query = MODE_QUERY_TEMPLATE.replace("__MODE__", method);
                QueryFactory.create(query);

                for (int w = 0; w < warmup; w++) {
                    executeModeQuery(endpoint, query, nPairs);
                }

                double[][] runsByPair = new double[nPairs][repeat];
                long totalNs = 0;
                for (int r = 0; r < repeat; r++) {
                    long t0 = System.nanoTime();
                    double[] run = executeModeQuery(endpoint, query, nPairs);
                    totalNs += System.nanoTime() - t0;
                    for (int i = 0; i < nPairs; i++) {
                        runsByPair[i][r] = run[i];
                    }
                }

                double[] estMean = new double[nPairs];
                double[] estStd = new double[nPairs];
                for (int i = 0; i < nPairs; i++) {
                    estMean[i] = mean(runsByPair[i]);
                    estStd[i] = std(runsByPair[i]);
                }

                boolean[] predLabels = new boolean[nPairs];
                for (int i = 0; i < nPairs; i++) {
                    predLabels[i] = estMean[i] <= THETA;
                }

                double[] cls = classificationMetrics(trueLabels, predLabels);
                double[] accMetrics = regressionMetrics(trueJSD, estMean);
                double latencyMs = totalNs / 1_000_000.0 / (nPairs * repeat);

                System.out.printf("acc=%.3f P=%.3f R=%.3f F1=%.3f MAE=%.5f %.2fms/pair%n",
                        cls[0], cls[1], cls[2], cls[3], accMetrics[0], latencyMs);

                aggRows.add(new String[]{method, dataset, fmt(cls[0]), fmt(cls[1]), fmt(cls[2]), fmt(cls[3]),
                        fmt6(accMetrics[0]), fmt6(accMetrics[1]), fmt3(latencyMs)});

                for (int i = 0; i < nPairs; i += 5) {
                    pairRows.add(new String[]{method, dataset, String.valueOf(refs.get(i).pairIndex()),
                            fmt6(trueJSD[i]), fmt6(estMean[i]), fmt6(estStd[i]),
                            trueLabels[i] ? "similar" : "dissimilar",
                            predLabels[i] ? "similar" : "dissimilar"});
                }
            }
            System.out.println();
        }

        writeCsv(outputDir + "/exp3_classification.csv", aggRows);
        writeCsv(outputDir + "/exp3_per_pair.csv", pairRows);
    }

    private static List<PairReference> loadReferences(String endpoint) {
        Map<Integer, Double> refs = new HashMap<>();
        RemoteBenchmarkClient.forEachSolution(endpoint, REF_QUERY, row -> {
            int idx = row.getLiteral("idx").getInt();
            double jsd = row.getLiteral("trueJSD").getDouble();
            refs.put(idx, jsd);
        });
        if (refs.isEmpty()) {
            throw new IllegalStateException("Remote Exp3 dataset has no prob:referenceJSD rows: " + endpoint);
        }
        List<Integer> indexes = new ArrayList<>(refs.keySet());
        indexes.sort(Integer::compareTo);
        List<PairReference> result = new ArrayList<>();
        for (int idx : indexes) {
            result.add(new PairReference(idx, refs.get(idx)));
        }
        return result;
    }

    private static double[] executeModeQuery(String endpoint, String query, int expected) {
        double[] values = new double[expected];
        boolean[] seen = new boolean[expected];
        final int[] count = {0};
        RemoteBenchmarkClient.forEachSolution(endpoint, query, row -> {
            int idx = row.getLiteral("idx").getInt();
            if (idx < 1 || idx > expected) {
                throw new IllegalStateException("Pair index out of range: " + idx);
            }
            values[idx - 1] = row.getLiteral("estJSD").getDouble();
            seen[idx - 1] = true;
            count[0]++;
        });
        if (count[0] != expected) {
            throw new IllegalStateException("Expected " + expected + " rows but got " + count[0]);
        }
        for (int i = 0; i < expected; i++) {
            if (!seen[i]) {
                throw new IllegalStateException("Missing pair index " + (i + 1));
            }
        }
        return values;
    }

    private static double[] classificationMetrics(boolean[] truth, boolean[] pred) {
        int tp = 0, fp = 0, tn = 0, fn = 0;
        for (int i = 0; i < truth.length; i++) {
            if (truth[i] && pred[i]) tp++;
            if (!truth[i] && pred[i]) fp++;
            if (!truth[i] && !pred[i]) tn++;
            if (truth[i] && !pred[i]) fn++;
        }
        int n = truth.length;
        double acc = n > 0 ? (double) (tp + tn) / n : 0;
        double prec = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double rec = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (prec + rec) > 0 ? 2.0 * prec * rec / (prec + rec) : 0;
        return new double[]{acc, prec, rec, f1};
    }

    private static double[] regressionMetrics(double[] truth, double[] est) {
        double sumAbs = 0;
        double sumSq = 0;
        for (int i = 0; i < truth.length; i++) {
            double e = Math.abs(est[i] - truth[i]);
            sumAbs += e;
            sumSq += e * e;
        }
        return new double[]{sumAbs / truth.length, Math.sqrt(sumSq / truth.length)};
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double std(double[] a) {
        double m = mean(a);
        double s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / a.length);
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.4f", v);
    }

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static String fmt6(double v) {
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) {
                pw.println(String.join(",", row));
            }
        }
        System.out.println("Wrote " + (rows.size() - 1) + " data rows -> " + path);
    }

    private record PairReference(int pairIndex, double referenceJSD) {}
}
