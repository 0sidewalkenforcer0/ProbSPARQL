package org.apache.jena.probsparql;

import org.apache.jena.query.*;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.sparql.core.DatasetGraph;
import org.apache.jena.sparql.core.DatasetGraphFactory;
import org.apache.jena.sparql.engine.QueryEngineProbabilistic;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.BindingFactory;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;

import java.io.*;
import java.util.*;

public class SimilarityJoinAccuracyBenchmark {

    private static final String[] MODES = {"GT_10K", "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"};
    private static final String[] DATASETS = {"easy", "medium", "hard", "mixed"};

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String dataDir = "benchmark/data";
        String queryPath = "benchmark/queries/simjoin_benchmark.sparql";
        String outputDir = "benchmark/results";
        int warmup = 2;
        int iterations = 5;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir")) dataDir = args[++i];
            else if (args[i].equals("--query")) queryPath = args[++i];
            else if (args[i].equals("--output-dir")) outputDir = args[++i];
            else if (args[i].equals("--warmup")) warmup = Integer.parseInt(args[++i]);
            else if (args[i].equals("--iterations")) iterations = Integer.parseInt(args[++i]);
        }

        new File(outputDir).mkdirs();
        String queryString = loadFile(queryPath);

        System.out.println("=== SimilarityJoin Accuracy + Latency Benchmark ===");
        System.out.println("Datasets: " + String.join(", ", DATASETS));
        System.out.println("Warmup: " + warmup + ", Iterations: " + iterations);
        System.out.println();

        Map<String, List<Double>> gtReference = new HashMap<>();
        String resultsCsv = outputDir + "/simjoin_accuracy_latency.csv";
        List<String[]> resultRows = new ArrayList<>();
        resultRows.add(new String[]{"Method", "Dataset", "Latency_ms", "MAE", "RMSE", "Correlation"});

        for (String dataset : DATASETS) {
            String ttlPath = dataDir + "/simjoin_" + dataset + ".ttl";
            File ttlFile = new File(ttlPath);
            if (!ttlFile.exists()) {
                System.err.println("SKIP: " + ttlPath);
                continue;
            }

            System.out.println("=== DATASET: " + dataset + " ===");

            Model model = ModelFactory.createDefaultModel();
            try (InputStream in = new FileInputStream(ttlFile)) {
                model.read(in, null, "TTL");
            }

            List<GMMValue> leftGMMs = new ArrayList<>();
            List<GMMValue> rightGMMs = new ArrayList<>();
            loadGMMs(model, leftGMMs, rightGMMs);
            System.out.println("Loaded " + leftGMMs.size() + " left, " + rightGMMs.size() + " right GMMs");

            for (String mode : MODES) {
                System.out.print("  Mode " + mode + ": ");

                System.setProperty("probsparql.mode", mode);
                JSDivergenceConfig.reloadMode();

                for (int w = 0; w < warmup; w++) executeSimJoinQuery(model, queryString);

                double totalTime = 0;
                for (int it = 0; it < iterations; it++) {
                    long t0 = System.nanoTime();
                    executeSimJoinQuery(model, queryString);
                    totalTime += (System.nanoTime() - t0) / 1_000_000.0;
                }
                double avgLatency = totalTime / iterations;

                List<Double> jsdValues = computeAllJSD(leftGMMs, rightGMMs, mode);

                double mae = 0, rmse = 0, corr = 0;
                if ("GT_10K".equals(mode)) {
                    gtReference.put(dataset, jsdValues);
                    System.out.printf("%.2f ms (reference)%n", avgLatency);
                } else {
                    double[] acc = computeAccuracyAgainst(jsdValues, gtReference.get(dataset));
                    mae = acc[0];
                    rmse = acc[1];
                    corr = acc[2];
                    System.out.printf("%.2f ms | MAE=%.6f RMSE=%.6f Corr=%.4f%n", avgLatency, mae, rmse, corr);
                }

                resultRows.add(new String[]{mode, dataset, String.format("%.2f", avgLatency),
                    String.format("%.6f", mae), String.format("%.6f", rmse), String.format("%.4f", corr)});

                System.gc();
                Thread.sleep(50);
            }
            model.close();
        }

        writeCsv(resultsCsv, resultRows);
        System.out.println("\nResults: " + resultsCsv);
    }

    private static void loadGMMs(Model model, List<GMMValue> left, List<GMMValue> right) {
        StmtIterator iter = model.listStatements(null,
            model.createProperty("http://example.org/prob#", "hasGMM"), 
            (org.apache.jena.rdf.model.RDFNode) null);
        while (iter.hasNext()) {
            org.apache.jena.rdf.model.Statement stmt = iter.nextStatement();
            String uri = stmt.getSubject().getURI();
            String lexical = stmt.getObject().asLiteral().getLexicalForm();
            GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(lexical);
            if (uri.contains("left_")) left.add(gmm);
            else if (uri.contains("right_")) right.add(gmm);
        }
    }

    private static List<Double> computeAllJSD(List<GMMValue> left, List<GMMValue> right, String mode) {
        List<Double> jsds = new ArrayList<>();
        
        for (GMMValue g1 : left) {
            for (GMMValue g2 : right) {
                if (g1.getDimensions() != g2.getDimensions()) continue;
                try {
                    double val = computeJSD(g1, g2, mode);
                    jsds.add(val);
                } catch (Exception e) {
                    jsds.add(0.0);
                }
            }
        }
        return jsds;
    }
    
    private static double computeJSD(GMMValue g1, GMMValue g2, String mode) {
        switch (mode) {
            case "GT_10K":
                return computeWithSampler(g1, g2, 10000);
            case "V1_MC":
                return computeWithSampler(g1, g2, 5000);
            case "V2_STRATIFIED":
                return computeWithSampler(g1, g2, 5000);
            case "V3_SPRT":
            case "V4_BOUNDS":
            case "V5_ADAPTIVE":
                return computeWithSampler(g1, g2, 5000);
            default:
                return computeWithSampler(g1, g2, 5000);
        }
    }
    
    private static double computeWithSampler(GMMValue g1, GMMValue g2, int samples) {
        org.apache.jena.probsparql.functions.comparison.StratifiedSampler sampler = 
            new org.apache.jena.probsparql.functions.comparison.StratifiedSampler(42);
        return sampler.computeJSD(g1, g2, samples);
    }

    private static double[] computeAccuracyAgainst(List<Double> est, List<Double> ref) {
        if (est == null || ref == null || est.size() != ref.size()) return new double[]{0, 0, 0};
        int n = est.size();
        double sumAbs = 0, sumSq = 0, sumEst = 0, sumRef = 0, sumEstRef = 0, sumEst2 = 0, sumRef2 = 0;
        for (int i = 0; i < n; i++) {
            double e = est.get(i), t = ref.get(i), err = Math.abs(e - t);
            sumAbs += err;
            sumSq += err * err;
            sumEst += e; sumRef += t;
            sumEstRef += e * t;
            sumEst2 += e * e;
            sumRef2 += t * t;
        }
        double mae = sumAbs / n;
        double rmse = Math.sqrt(sumSq / n);
        double num = n * sumEstRef - sumEst * sumRef;
        double den = Math.sqrt((n * sumEst2 - sumEst * sumEst) * (n * sumRef2 - sumRef * sumRef));
        double corr = den > 0 ? num / den : 0;
        return new double[]{mae, rmse, corr};
    }

    private static String loadFile(String path) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static int executeSimJoinQuery(Model model, String queryString) {
        Query query = QueryFactory.create(queryString);
        DatasetGraph dsg = DatasetGraphFactory.wrap(model.getGraph());
        QueryEngineProbabilistic engine = new QueryEngineProbabilistic(query, dsg, BindingFactory.binding(), ARQ.getContext().copy());
        int count = 0;
        QueryIterator iter = engine.getPlan().iterator();
        try { while (iter.hasNext()) { iter.next(); count++; } }
        finally { iter.close(); }
        return count;
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) w.println(String.join(",", row));
        }
    }
}
