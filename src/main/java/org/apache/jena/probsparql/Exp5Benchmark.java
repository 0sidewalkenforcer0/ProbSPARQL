package org.apache.jena.probsparql;

import org.apache.jena.graph.Node;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.probsparql.functions.thresholding.CDF;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Exp5 — In-engine early probabilistic filter vs post-processing late filter.
 *
 * <p>A uses the query-internal early filter over the CT tooth-length
 * distribution in an Exp1-style measurement graph:
 *   ?gear cfm:hasLengthCharacteristic ?char .
 *   ?ctMeasurement a ag:CTMeasurement ; cfm:representedBy ?rv .
 *   ?rv cfm:hasDistribution ?d .
 *   FILTER(prob:cdf(?d, 9.8) >= 0.9)
 *   OPTIONAL { SL / laser follow-up measurement distributions }
 *
 * <p>B fetches all rows from the remote Fuseki endpoint first, including the
 * OPTIONAL expansion, and only then applies the cdf threshold in Java.
 */
public class Exp5Benchmark {

    private static int WARMUP_RUNS = 3;
    private static int BENCHMARK_RUNS = 10;
    private static final double CDF_POINT = 9.8;
    private static final double CDF_THRESHOLD = 0.9;
    private static final CDF CDF_FUNC = new CDF();

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String queryDir = "benchmark/queries/exp5";
        String outputDir = "benchmark/results/exp5";
        String endpointTemplate = null;
        String datasetName = null;

        for (int i = 0; i < args.length; i++) {
            if ("--endpoint-template".equals(args[i]) && i + 1 < args.length) endpointTemplate = args[++i];
            else if ("--dataset".equals(args[i]) && i + 1 < args.length) datasetName = args[++i];
            else if ("--query-dir".equals(args[i]) && i + 1 < args.length) queryDir = args[++i];
            else if ("--output-dir".equals(args[i]) && i + 1 < args.length) outputDir = args[++i];
            else if ("--warmup".equals(args[i]) && i + 1 < args.length) WARMUP_RUNS = Integer.parseInt(args[++i]);
            else if ("--runs".equals(args[i]) && i + 1 < args.length) BENCHMARK_RUNS = Integer.parseInt(args[++i]);
        }
        if (endpointTemplate == null || endpointTemplate.isBlank()) {
            throw new IllegalArgumentException("Missing required --endpoint-template");
        }
        if (datasetName == null || datasetName.isBlank()) {
            throw new IllegalArgumentException("Missing required --dataset");
        }

        new File(outputDir).mkdirs();
        String endpoint = RemoteBenchmarkClient.endpointFor(endpointTemplate, datasetName);

        String qA = readFile(queryDir + "/inengine_early_filter.sparql");
        String qB = readFile(queryDir + "/postprocessing_fetch_all.sparql");
        QueryFactory.create(qA);
        QueryFactory.create(qB);

        for (int i = 0; i < WARMUP_RUNS; i++) {
            executeQuery(endpoint, qA);
            executeLateFilter(endpoint, qB);
        }

        TimingResult a = measureQuery(endpoint, qA);
        LateFilterResult b = measureLateFilter(endpoint, qB);

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{
            "Method", "MedianMs", "IQRMs", "RowsReturned",
            "DistinctGears", "FetchedRowsBeforeFilter", "DistinctGearsBeforeFilter"
        });
        rows.add(new String[]{
            "InEngine_EarlyFilter", fmt(a.medianMs), fmt(a.iqrMs),
            Integer.toString(a.rowsReturned), Integer.toString(a.distinctGears), "", ""
        });
        rows.add(new String[]{
            "PostProcessing_LateFilter", fmt(b.medianMs), fmt(b.iqrMs),
            Integer.toString(b.rowsAfterFilter), Integer.toString(b.distinctGearsAfterFilter),
            Integer.toString(b.rowsBeforeFilter), Integer.toString(b.distinctGearsBeforeFilter)
        });

        writeCsv(outputDir + "/exp5_summary.csv", rows);

        System.out.println("=== Exp5: In-engine vs Post-processing ===");
        System.out.printf("Endpoint: %s%n", endpoint);
        System.out.printf("InEngine_EarlyFilter        median=%.3f ms  rows=%d gears=%d%n",
            a.medianMs, a.rowsReturned, a.distinctGears);
        System.out.printf("PostProcessing_LateFilter   median=%.3f ms  rows=%d gears=%d  fetchedRows=%d fetchedGears=%d%n",
            b.medianMs, b.rowsAfterFilter, b.distinctGearsAfterFilter, b.rowsBeforeFilter, b.distinctGearsBeforeFilter);
        System.out.println("Results → " + outputDir + "/exp5_summary.csv");
    }

    private static TimingResult measureQuery(String endpoint, String sparql) {
        long[] times = new long[BENCHMARK_RUNS];
        int rows = 0;
        int gears = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            QueryStats stats = executeQuery(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
            rows = stats.rows;
            gears = stats.distinctGears;
        }
        Arrays.sort(times);
        return new TimingResult(ms(times[BENCHMARK_RUNS / 2]), ms(iqr(times)), rows, gears);
    }

    private static LateFilterResult measureLateFilter(String endpoint, String sparql) {
        long[] times = new long[BENCHMARK_RUNS];
        LateFilterStats last = null;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            last = executeLateFilter(endpoint, sparql);
            times[i] = System.nanoTime() - t0;
        }
        Arrays.sort(times);
        return new LateFilterResult(
            ms(times[BENCHMARK_RUNS / 2]),
            ms(iqr(times)),
            last.rowsAfterFilter,
            last.distinctGearsAfterFilter,
            last.rowsBeforeFilter,
            last.distinctGearsBeforeFilter
        );
    }

    private static QueryStats executeQuery(String endpoint, String sparql) {
        RemoteBenchmarkClient.QueryStats stats = RemoteBenchmarkClient.execStats(endpoint, sparql, "gear");
        return new QueryStats(stats.rows(), stats.distinct());
    }

    private static LateFilterStats executeLateFilter(String endpoint, String sparql) {
        int rowsBefore = 0;
        int rowsAfter = 0;
        Set<String> gearsBefore = new LinkedHashSet<>();
        Set<String> gearsAfter = new LinkedHashSet<>();

        final int[] rowCounts = {rowsBefore, rowsAfter};
        RemoteBenchmarkClient.forEachSolution(endpoint, sparql, sol -> {
            rowCounts[0]++;
            String gear = sol.get("gear").toString();
            gearsBefore.add(gear);

            Node dNode = sol.get("d").asNode();
            if (cdf(dNode, CDF_POINT) >= CDF_THRESHOLD) {
                rowCounts[1]++;
                gearsAfter.add(gear);
            }
        });
        return new LateFilterStats(rowCounts[0], gearsBefore.size(), rowCounts[1], gearsAfter.size());
    }

    private static double cdf(Node distNode, double point) {
        return CDF_FUNC.exec(NodeValue.makeNode(distNode), NodeValue.makeDouble(point)).getDouble();
    }

    private static long iqr(long[] sorted) {
        return sorted[(sorted.length * 3) / 4] - sorted[sorted.length / 4];
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }
    private static String fmt(double v) { return String.format(java.util.Locale.ROOT, "%.3f", v); }

    private static String readFile(String path) throws IOException {
        return java.nio.file.Files.readString(java.nio.file.Path.of(path));
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
    }

    private record QueryStats(int rows, int distinctGears) {}
    private record TimingResult(double medianMs, double iqrMs, int rowsReturned, int distinctGears) {}
    private record LateFilterStats(int rowsBeforeFilter, int distinctGearsBeforeFilter,
                                   int rowsAfterFilter, int distinctGearsAfterFilter) {}
    private record LateFilterResult(double medianMs, double iqrMs, int rowsAfterFilter,
                                    int distinctGearsAfterFilter, int rowsBeforeFilter,
                                    int distinctGearsBeforeFilter) {}
}
