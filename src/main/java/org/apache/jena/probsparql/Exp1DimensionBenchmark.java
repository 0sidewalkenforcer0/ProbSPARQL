package org.apache.jena.probsparql;

import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.query.Query;
import org.apache.jena.query.QueryExecution;
import org.apache.jena.query.QueryExecutionFactory;
import org.apache.jena.query.QueryFactory;
import org.apache.jena.query.ResultSet;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.RDFDataMgr;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Exp1 supplement: fixed scale E5, fixed K=3, varying GMM dimension d in
 * {1, 2, 4, 8}.
 * Runs Q1-Q3 with deterministic and probabilistic paths on the same multidimensional
 * datasets, plus two Q4 probabilistic variants.
 */
public class Exp1DimensionBenchmark {

    private static int WARMUP_RUNS = 3;
    private static int BENCHMARK_RUNS = 10;

    private static final String DEFAULT_SCALE = "E5";
    private static final int DEFAULT_K = 3;
    private static final int[] DEFAULT_DIMENSIONS = {1, 2, 4, 8};

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String dataDir = "benchmark/data/exp1/dimension";
        String queryDir = "benchmark/queries/exp1/dimension";
        String outputDir = "benchmark/results/exp1/dimension";
        String scale = DEFAULT_SCALE;
        int k = DEFAULT_K;
        List<Integer> dimensions = new ArrayList<>();
        for (int d : DEFAULT_DIMENSIONS) dimensions.add(d);

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--data-dir" -> dataDir = args[++i];
                case "--query-dir" -> queryDir = args[++i];
                case "--output-dir" -> outputDir = args[++i];
                case "--scale" -> scale = args[++i];
                case "--k" -> k = Integer.parseInt(args[++i]);
                case "--dimensions" -> {
                    dimensions.clear();
                    while (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                        dimensions.add(Integer.parseInt(args[++i]));
                    }
                }
                case "--warmup" -> WARMUP_RUNS = Integer.parseInt(args[++i]);
                case "--runs" -> BENCHMARK_RUNS = Integer.parseInt(args[++i]);
                default -> System.err.println("Unknown flag: " + args[i]);
            }
        }

        new File(outputDir).mkdirs();
        List<String[]> rawRows = new ArrayList<>();
        List<String[]> sumRows = new ArrayList<>();

        System.out.println("=== Exp1 Dimension Scaling (Q1-Q4) ===");
        System.out.printf("Scale      : %s%n", scale);
        System.out.printf("K          : %d%n", k);
        System.out.printf("Dimensions : %s%n", dimensions);
        System.out.printf("Warmup     : %d%n", WARMUP_RUNS);
        System.out.printf("Runs       : %d%n", BENCHMARK_RUNS);
        System.out.printf("Data dir   : %s%n", dataDir);
        System.out.printf("Query dir  : %s%n", queryDir);
        System.out.printf("Output dir : %s%n", outputDir);
        System.out.println();

        for (int d : dimensions) {
            String ttl = dataDir + "/exp1_" + scale + "_K" + k + "_D" + d + ".ttl";
            System.out.printf("Loading D=%d : %s%n", d, ttl);

            Model model = RDFDataMgr.loadModel(ttl);
            Dataset ds = DatasetFactory.create(model);

            Map<String, Double> detMedians = new HashMap<>();

            for (String qid : List.of("Q1", "Q2", "Q3")) {
                Query detQuery = loadQuery(queryDir + "/det/" + qid.toLowerCase() + ".sparql");
                RunResult det = runTimed(ds, detQuery);
                detMedians.put(qid, median(det.timesNs));
                addRows(rawRows, sumRows, scale, k, d, qid, "DET", "STD", det, "—");
            }

            Query q1Prob = loadQuery(queryDir + "/prob/q1.sparql");
            Query q2Prob = loadQueryWithReplacement(
                    queryDir + "/prob/q2.sparql",
                    "__CDF_POINT__",
                    cdfPointLiteral(d)
            );
            Query q3Prob = loadQuery(queryDir + "/prob/q3.sparql");
            Query q4Legacy = loadQuery(queryDir + "/prob/q4.sparql");
            Query q4Jsd = loadQuery(queryDir + "/prob/q4_jsd.sparql");

            addProbRows(rawRows, sumRows, scale, k, d, "Q1", "STD", runTimed(ds, q1Prob), detMedians.get("Q1"));
            addProbRows(rawRows, sumRows, scale, k, d, "Q2", "STD", runTimed(ds, q2Prob), detMedians.get("Q2"));
            addProbRows(rawRows, sumRows, scale, k, d, "Q3", "STD", runTimed(ds, q3Prob), detMedians.get("Q3"));
            addRows(rawRows, sumRows, scale, k, d, "Q4", "PROB", "JSDIVERGENCE", runTimed(ds, q4Legacy), "—");
            addRows(rawRows, sumRows, scale, k, d, "Q4", "PROB", "JSD", runTimed(ds, q4Jsd), "—");

            ds.close();
            model.close();
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/exp1_dimension_raw.csv"))) {
            pw.println("Scale,K,Dimension,QueryID,Type,Variant,Run,Time_ms,ResultCount");
            for (String[] row : rawRows) pw.println(String.join(",", row));
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(outputDir + "/exp1_dimension_summary.csv"))) {
            pw.println("Scale,K,Dimension,QueryID,Type,Variant,Median_ms,IQR_ms,ResultCount,OverheadRatio");
            for (String[] row : sumRows) pw.println(String.join(",", row));
        }
    }

    private static void addProbRows(
            List<String[]> rawRows,
            List<String[]> sumRows,
            String scale,
            int k,
            int d,
            String qid,
            String variant,
            RunResult result,
            double detMedian
    ) {
        String ratio = detMedian > 0 ? fmt4(median(result.timesNs) / detMedian) : "NaN";
        addRows(rawRows, sumRows, scale, k, d, qid, "PROB", variant, result, ratio);
    }

    private static void addRows(
            List<String[]> rawRows,
            List<String[]> sumRows,
            String scale,
            int k,
            int d,
            String qid,
            String type,
            String variant,
            RunResult result,
            String ratio
    ) {
        double medMs = median(result.timesNs);
        double iqrMs = iqr(result.timesNs);
        for (int r = 0; r < BENCHMARK_RUNS; r++) {
            rawRows.add(new String[]{
                    scale,
                    String.valueOf(k),
                    String.valueOf(d),
                    qid,
                    type,
                    variant,
                    String.valueOf(r + 1),
                    fmt4(result.timesNs[r] / 1e6),
                    String.valueOf(result.resultCount)
            });
        }

        sumRows.add(new String[]{
                scale,
                String.valueOf(k),
                String.valueOf(d),
                qid,
                type,
                variant,
                fmt4(medMs),
                fmt4(iqrMs),
                String.valueOf(result.resultCount),
                ratio
        });
    }

    private static Query loadQuery(String path) throws IOException {
        return QueryFactory.create(Files.readString(Path.of(path)));
    }

    private static Query loadQueryWithReplacement(String path, String placeholder, String replacement) throws IOException {
        String sparql = Files.readString(Path.of(path));
        return QueryFactory.create(sparql.replace(placeholder, replacement));
    }

    private static String cdfPointLiteral(int d) {
        StringBuilder sb = new StringBuilder("\"[");
        for (int i = 0; i < d; i++) {
            if (i > 0) sb.append(",");
            double v = (i == 0) ? 9.8 : 0.25 * i + 0.075;
            sb.append(String.format(Locale.ROOT, "%.3f", v));
        }
        sb.append("]\"");
        return sb.toString();
    }

    private static RunResult runTimed(Dataset ds, Query q) {
        for (int i = 0; i < WARMUP_RUNS; i++) drain(ds, q);
        long[] times = new long[BENCHMARK_RUNS];
        int count = 0;
        for (int i = 0; i < BENCHMARK_RUNS; i++) {
            long t0 = System.nanoTime();
            count = drain(ds, q);
            times[i] = System.nanoTime() - t0;
        }
        return new RunResult(times, count);
    }

    private static int drain(Dataset ds, Query q) {
        int count = 0;
        try (QueryExecution qe = QueryExecutionFactory.create(q, ds)) {
            ResultSet rs = qe.execSelect();
            while (rs.hasNext()) {
                rs.next();
                count++;
            }
        }
        return count;
    }

    private static double median(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        return s[s.length / 2] / 1_000_000.0;
    }

    private static double iqr(long[] arr) {
        long[] s = arr.clone();
        Arrays.sort(s);
        double q1 = s[Math.max(0, s.length / 4)] / 1_000_000.0;
        double q3 = s[Math.min(s.length - 1, 3 * s.length / 4)] / 1_000_000.0;
        return q3 - q1;
    }

    private static String fmt4(double v) {
        return Double.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.4f", v);
    }

    private record RunResult(long[] timesNs, int resultCount) {}
}
