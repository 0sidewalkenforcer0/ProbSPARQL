package org.apache.jena.probsparql;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.StratifiedSampler;

import java.io.*;
import java.util.*;

public class ConvergenceBenchmark {

    private static final int[] SAMPLE_COUNTS = {100, 200, 500, 1000, 2000, 5000, 10000};
    private static final String DATASET = "hard";

    public static void main(String[] args) throws Exception {
        String dataDir = "benchmark/data";
        String outputDir = "benchmark/results";
        int iterations = 3;

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir")) dataDir = args[++i];
            else if (args[i].equals("--output-dir")) outputDir = args[++i];
            else if (args[i].equals("--iterations")) iterations = Integer.parseInt(args[++i]);
        }

        new File(outputDir).mkdirs();

        String ttlPath = dataDir + "/simjoin_" + DATASET + ".ttl";
        System.out.println("=== Convergence Analysis: Stratified Sampling ===");
        System.out.println("Dataset: " + DATASET);
        System.out.println("Sample counts: " + Arrays.toString(SAMPLE_COUNTS));
        System.out.println("Iterations: " + iterations);
        System.out.println();

        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = new FileInputStream(ttlPath)) {
            model.read(in, null, "TTL");
        }

        List<GMMValue> leftGMMs = new ArrayList<>();
        List<GMMValue> rightGMMs = new ArrayList<>();
        loadGMMs(model, leftGMMs, rightGMMs);
        System.out.println("Loaded " + leftGMMs.size() + " left, " + rightGMMs.size() + " right GMMs");
        System.out.println("Total pairs: " + (leftGMMs.size() * rightGMMs.size()));
        System.out.println();

        System.out.println("Computing GT reference (10k samples)...");
        List<Double> gtValues = computeWithStratified(leftGMMs, rightGMMs, 10000);
        System.out.println("GT computed: " + gtValues.size() + " values");
        
        double gtMean = gtValues.stream().mapToDouble(d -> d).average().orElse(0);
        System.out.println("GT mean JSD: " + String.format("%.4f", gtMean));
        System.out.println();

        String csvPath = outputDir + "/simjoin_convergence.csv";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Method", "Samples", "Iteration", "MAE", "RMSE", "Time_ms"});

        for (int samples : SAMPLE_COUNTS) {
            System.out.println("=== Samples: " + samples + " ===");

            System.out.print("  Computing: ");
            double totalTime = 0;
            double totalMAE = 0;
            double totalRMSE = 0;

            for (int it = 0; it < iterations; it++) {
                long t0 = System.nanoTime();
                List<Double> jsdValues = computeWithStratified(leftGMMs, rightGMMs, samples);
                long t1 = System.nanoTime();
                double time = (t1 - t0) / 1_000_000.0;
                totalTime += time;

                double[] acc = computeAccuracy(jsdValues, gtValues);
                totalMAE += acc[0];
                totalRMSE += acc[1];

                rows.add(new String[]{"Stratified", String.valueOf(samples), String.valueOf(it + 1),
                    String.format("%.6f", acc[0]), String.format("%.6f", acc[1]), String.format("%.2f", time)});

                System.gc();
                Thread.sleep(50);
            }

            double avgMAE = totalMAE / iterations;
            double avgRMSE = totalRMSE / iterations;
            double avgTime = totalTime / iterations;

            System.out.printf("MAE=%.6f RMSE=%.6f Time=%.2fms%n", avgMAE, avgRMSE, avgTime);
        }

        writeCsv(csvPath, rows);
        System.out.println("\nResults: " + csvPath);

        model.close();
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

    private static List<Double> computeWithStratified(List<GMMValue> left, List<GMMValue> right, int samples) {
        List<Double> jsds = new ArrayList<>();
        StratifiedSampler sampler = new StratifiedSampler(42);
        
        for (GMMValue g1 : left) {
            for (GMMValue g2 : right) {
                if (g1.getDimensions() != g2.getDimensions()) continue;
                try {
                    double val = sampler.computeJSD(g1, g2, samples);
                    jsds.add(val);
                } catch (Exception e) {
                    jsds.add(0.0);
                }
            }
        }
        return jsds;
    }

    private static double[] computeAccuracy(List<Double> est, List<Double> ref) {
        if (est == null || ref == null || est.size() != ref.size()) return new double[]{0, 0};
        int n = est.size();
        double sumAbs = 0, sumSq = 0;
        for (int i = 0; i < n; i++) {
            double err = Math.abs(est.get(i) - ref.get(i));
            sumAbs += err;
            sumSq += err * err;
        }
        return new double[]{sumAbs / n, Math.sqrt(sumSq / n)};
    }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter w = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) w.println(String.join(",", row));
        }
    }
}
