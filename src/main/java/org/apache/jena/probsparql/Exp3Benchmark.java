package org.apache.jena.probsparql;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.*;

import java.io.*;
import java.util.*;
import java.util.TreeMap;

/**
 * Exp 3 main benchmark: similarity classification accuracy across difficulty levels.
 *
 * Evaluates each sampling method on classification accuracy when deciding
 * whether JSD(P, Q) ≤ θ=0.3 (similar) or JSD(P, Q) > 0.3 (dissimilar).
 *
 * Methods compared (matching experiment plan §6.2.3):
 *   M1 = V1_MC       (plain Monte Carlo)
 *   M2 = V2_STRATIFIED (stratified sampling)
 *   M3 = V3_SPRT     (sequential probability ratio test)
 *   M4 = V4_BOUNDS   (analytical bounds filter + stratified)
 *   M5 = V5_ADAPTIVE (full adaptive pipeline)
 *
 * Ground truth: simjoin_ground_truth.csv generated alongside the TTL datasets.
 * Paper-aligned datasets contain 2,400 aligned pairs per difficulty workload,
 * with reference JSD values estimated by the data generator using 10^6 Monte
 * Carlo samples.
 *
 * Output CSV: benchmark/results/exp3/exp3_classification.csv
 *   columns: Method, Dataset, Accuracy, Precision, Recall, F1, MAE, RMSE, Latency_ms
 *
 * Per-pair CSV: benchmark/results/exp3/exp3_per_pair.csv
 *   columns: Method, Dataset, PairIdx, TrueJSD, EstJSD, TrueLabel, PredLabel
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp3Benchmark"
 */
public class Exp3Benchmark {

    // -----------------------------------------------------------------------
    // Experiment configuration
    // -----------------------------------------------------------------------
    private static final double THETA = 0.3;           // classification threshold
    private static final int    EVAL_SAMPLES = 10_000; // fixed-budget MC samples per decision
    private static int warmup = 3;                     // discarded warm-up repeats per (method, pair)
    private static int repeat = 10;                    // measured repeats per (method, pair)

    private static final String[] DATASETS = {"easy", "medium", "hard", "mixed"};

    /** Sampling method labels aligned with the experiment plan. */
    private static final String GT_LABEL = "GT_CSV";
    private static final String[] METHODS = {
        "V1_MC",
        "V2_STRATIFIED",
        "V3_SPRT",
        "V4_BOUNDS",
        "V5_ADAPTIVE"
    };

    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String dataDir   = "benchmark/data/exp3";
        String outputDir = "benchmark/results/exp3";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir"))       dataDir   = args[++i];
            else if (args[i].equals("--output-dir")) outputDir = args[++i];
            else if (args[i].equals("--warmup"))      warmup    = Integer.parseInt(args[++i]);
            else if (args[i].equals("--repeat"))     repeat    = Integer.parseInt(args[++i]);
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 3: Classification Accuracy ===");
        System.out.printf("Threshold θ = %.1f%n", THETA);
        System.out.println("Ground truth: simjoin_ground_truth.csv");
        System.out.println("Methods     : " + Arrays.toString(METHODS));
        System.out.println("Datasets    : " + Arrays.toString(DATASETS));
        System.out.println("Warmup      : " + warmup);
        System.out.println("Runs        : " + repeat);
        System.out.println();

        // Aggregate results
        String aggCsv  = outputDir + "/exp3_classification.csv";
        String pairCsv = outputDir + "/exp3_per_pair.csv";
        String gtCsv   = dataDir + "/simjoin_ground_truth.csv";

        List<String[]> aggRows  = new ArrayList<>();
        List<String[]> pairRows = new ArrayList<>();

        aggRows.add(new String[]{
            "Method", "Dataset",
            "Accuracy", "Precision", "Recall", "F1",
            "MAE", "RMSE", "Latency_ms"
        });
        pairRows.add(new String[]{
            "Method", "Dataset", "PairIdx",
            "TrueJSD", "EstJSD_mean", "EstJSD_std",
            "TrueLabel", "PredLabel"
        });

        // ----------------------------------------------------------------
        // Samplers (fixed seeds for reproducibility)
        // ----------------------------------------------------------------
        StratifiedSampler  stratified = new StratifiedSampler(42);
        SPRTSampler        sprt       = new SPRTSampler(42,
                JSDivergenceConfig.SPRT_ALPHA,
                JSDivergenceConfig.SPRT_BETA,
                JSDivergenceConfig.SPRT_EPSILON);
        BoundsFilterSampler bounds    = new BoundsFilterSampler(JSDivergenceConfig.SPRT_EPSILON);
        AdaptiveSampler    adaptive   = new AdaptiveSampler(JSDivergenceConfig.SPRT_EPSILON,
                JSDivergenceConfig.SPRT_ALPHA,
                JSDivergenceConfig.SPRT_BETA,
                JSDivergenceConfig.SPRT_EPSILON);

        // ----------------------------------------------------------------
        // Run over all datasets
        // ----------------------------------------------------------------
        Map<String, double[]> groundTruth = loadGroundTruth(gtCsv);

        for (String dataset : DATASETS) {
            String ttlPath = dataDir + "/simjoin_" + dataset + ".ttl";
            if (!new File(ttlPath).exists()) {
                System.err.println("SKIP (not found): " + ttlPath);
                continue;
            }

            // Load model
            Model model = ModelFactory.createDefaultModel();
            try (InputStream in = new FileInputStream(ttlPath)) {
                model.read(in, null, "TTL");
            }

            // Extract GMM pairs — loaded as sorted parallel arrays (left_i ↔ right_i)
            List<GMMValue> leftGMMs  = new ArrayList<>();
            List<GMMValue> rightGMMs = new ArrayList<>();
            loadGMMsAligned(model, leftGMMs, rightGMMs);
            int nPairs = Math.min(leftGMMs.size(), rightGMMs.size());
            System.out.printf("Dataset %-6s : %d aligned pairs%n", dataset, nPairs);

            double[] gtJSD = requireGroundTruth(dataset, nPairs, groundTruth);
            boolean[] trueLabels = new boolean[nPairs];
            for (int i = 0; i < nPairs; i++) trueLabels[i] = (gtJSD[i] <= THETA);

            // Aggregate row for CSV ground truth
            {
                double[] cls = classificationMetrics(trueLabels, trueLabels); // perfect
                aggRows.add(new String[]{
                    GT_LABEL, dataset,
                    fmt(cls[0]), fmt(cls[1]), fmt(cls[2]), fmt(cls[3]),
                    "0.000000", "0.000000", "0.000"
                });
            }

            // ----------------------------------------------------------------
            // Evaluate each sampling method
            // ----------------------------------------------------------------
            for (String method : METHODS) {
                System.out.printf("  Method %-15s: ", method);
                long totalNs = 0;

                // For each aligned pair, run REPEAT times and average the estimate
                double[] estMean = new double[nPairs];
                double[] estStd  = new double[nPairs];

                for (int i = 0; i < nPairs; i++) {
                    GMMValue g1 = leftGMMs.get(i);
                    GMMValue g2 = rightGMMs.get(i);
                    if (g1.getDimensions() != g2.getDimensions()) continue;

                    for (int w = 0; w < warmup; w++) {
                        computeJSD(g1, g2, method,
                                EVAL_SAMPLES, stratified, sprt, bounds, adaptive);
                    }

                    double[] runs = new double[repeat];
                    for (int r = 0; r < repeat; r++) {
                        long t0 = System.nanoTime();
                        runs[r] = computeJSD(g1, g2, method,
                                EVAL_SAMPLES, stratified, sprt, bounds, adaptive);
                        totalNs += System.nanoTime() - t0;
                    }
                    estMean[i] = mean(runs);
                    estStd[i]  = std(runs);
                }

                // Binary classification from mean estimate
                boolean[] predLabels = new boolean[nPairs];
                for (int i = 0; i < nPairs; i++) predLabels[i] = (estMean[i] <= THETA);

                double[] cls = classificationMetrics(trueLabels, predLabels);
                double[] accMetrics = regressionMetrics(gtJSD, estMean);
                double latencyMs = totalNs / 1_000_000.0 / (nPairs * repeat);

                System.out.printf("acc=%.3f  P=%.3f  R=%.3f  F1=%.3f  MAE=%.5f  %.2fms/pair%n",
                    cls[0], cls[1], cls[2], cls[3], accMetrics[0], latencyMs);

                aggRows.add(new String[]{
                    method, dataset,
                    fmt(cls[0]), fmt(cls[1]), fmt(cls[2]), fmt(cls[3]),
                    String.format(java.util.Locale.ROOT, "%.6f", accMetrics[0]),
                    String.format(java.util.Locale.ROOT, "%.6f", accMetrics[1]),
                    String.format(java.util.Locale.ROOT, "%.3f", latencyMs)
                });

                // Per-pair output (sampled 1-in-5 to keep CSV size manageable)
                for (int i = 0; i < nPairs; i += 5) {
                    pairRows.add(new String[]{
                        method, dataset, String.valueOf(i),
                        fmt6(gtJSD[i]), fmt6(estMean[i]), fmt6(estStd[i]),
                        trueLabels[i]  ? "similar" : "dissimilar",
                        predLabels[i]  ? "similar" : "dissimilar"
                    });
                }
            }

            model.close();
            System.out.println();
        }

        writeCsv(aggCsv,  aggRows);
        writeCsv(pairCsv, pairRows);
    }

    private static Map<String, double[]> loadGroundTruth(String path) throws IOException {
        File f = new File(path);
        if (!f.exists()) {
            throw new FileNotFoundException("Missing ground-truth CSV: " + path);
        }

        Map<String, TreeMap<Integer, Double>> byDataset = new HashMap<>();
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line = br.readLine(); // header
            if (line == null) {
                throw new IOException("Empty ground-truth CSV: " + path);
            }
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] parts = line.split(",", -1);
                if (parts.length < 3) continue;
                String dataset = parts[0];
                int pairIndex = Integer.parseInt(parts[1]);
                double jsd = Double.parseDouble(parts[2]);
                byDataset.computeIfAbsent(dataset, k -> new TreeMap<>()).put(pairIndex, jsd);
            }
        }

        Map<String, double[]> result = new HashMap<>();
        for (Map.Entry<String, TreeMap<Integer, Double>> e : byDataset.entrySet()) {
            TreeMap<Integer, Double> rows = e.getValue();
            int n = rows.isEmpty() ? 0 : rows.lastKey();
            double[] values = new double[n];
            for (Map.Entry<Integer, Double> row : rows.entrySet()) {
                values[row.getKey() - 1] = row.getValue();
            }
            result.put(e.getKey(), values);
        }
        return result;
    }

    private static double[] requireGroundTruth(String dataset, int nPairs, Map<String, double[]> groundTruth) {
        double[] values = groundTruth.get(dataset);
        if (values == null) {
            throw new IllegalArgumentException("Missing ground truth for dataset: " + dataset);
        }
        if (values.length < nPairs) {
            throw new IllegalArgumentException(
                    "Ground truth for dataset " + dataset + " has only " + values.length
                            + " rows, but benchmark loaded " + nPairs + " pairs");
        }
        return Arrays.copyOf(values, nPairs);
    }

    // -----------------------------------------------------------------------
    // JSD dispatch — routes to the correct sampler per method
    // -----------------------------------------------------------------------
    private static double computeJSD(GMMValue g1, GMMValue g2, String method,
            int samples,
            StratifiedSampler stratified,
            SPRTSampler sprt,
            BoundsFilterSampler bounds,
            AdaptiveSampler adaptive) {
        switch (method) {
            case "V1_MC":         return computeMC(g1, g2, samples);
            case "V2_STRATIFIED": return stratified.computeJSD(g1, g2, samples);
            case "V3_SPRT":       return sprt.computeJSD(g1, g2, samples);
            case "V4_BOUNDS":     return bounds.computeJSD(g1, g2, samples);
            case "V5_ADAPTIVE":   return adaptive.computeJSD(g1, g2, samples);
            default:              return computeMC(g1, g2, samples);
        }
    }

    private static double computeMC(GMMValue p, GMMValue q, int n) {
        Random rng = new Random(42);
        // Build mixture M = 0.5 P + 0.5 Q
        int kP = p.getNComponents(), kQ = q.getNComponents(), kM = kP + kQ, d = p.getDimensions();
        double[] wM = new double[kM];
        double[][] meansM = new double[kM][];
        double[][][] covsM = new double[kM][][];
        for (int k = 0; k < kP; k++) {
            wM[k] = 0.5 * p.getWeights()[k];
            meansM[k] = p.getMeans()[k];
            covsM[k]  = p.getCovariances()[k];
        }
        for (int k = 0; k < kQ; k++) {
            wM[kP + k] = 0.5 * q.getWeights()[k];
            meansM[kP + k] = q.getMeans()[k];
            covsM[kP + k]  = q.getCovariances()[k];
        }
        GMMValue m = new GMMValue(kM, d, p.getCovarianceType(), wM, meansM, covsM);

        int half = n / 2;
        double klPM = computeKLMC(p, m, half, rng);
        double klQM = computeKLMC(q, m, half, rng);
        return Math.max(0.0, 0.5 * (klPM + klQM));
    }

    private static double computeKLMC(GMMValue p, GMMValue q, int n, Random rng) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double[] x = p.sampleOne(rng);
            double logP = p.logPdf(x);
            double logQ = q.logPdf(x);
            sum += logP - logQ;
        }
        return sum / n;
    }

    // -----------------------------------------------------------------------
    // Model loading
    // -----------------------------------------------------------------------
    /**
     * Loads GMMs from the model into two parallel lists aligned by index.
     * left_001 is paired with right_001, etc. (sorted by subject URI).
     */
    private static void loadGMMsAligned(Model model, List<GMMValue> left, List<GMMValue> right) {
        Map<String, GMMValue> leftMap  = new TreeMap<>();
        Map<String, GMMValue> rightMap = new TreeMap<>();
        StmtIterator iter = model.listStatements(null,
            model.createProperty("http://example.org/prob#hasGMM"),
            (org.apache.jena.rdf.model.RDFNode) null);
        while (iter.hasNext()) {
            org.apache.jena.rdf.model.Statement s = iter.nextStatement();
            String uri = s.getSubject().getURI();
            String lex = s.getObject().asLiteral().getLexicalForm();
            GMMValue gmm = (GMMValue) GMMDatatype.INSTANCE.parse(lex);
            if (uri.contains("left_"))       leftMap.put(uri, gmm);
            else if (uri.contains("right_")) rightMap.put(uri, gmm);
        }
        List<String> lKeys = new ArrayList<>(leftMap.keySet());
        List<String> rKeys = new ArrayList<>(rightMap.keySet());
        int n = Math.min(lKeys.size(), rKeys.size());
        for (int i = 0; i < n; i++) {
            left.add(leftMap.get(lKeys.get(i)));
            right.add(rightMap.get(rKeys.get(i)));
        }
    }

    // -----------------------------------------------------------------------
    // Statistics helpers
    // -----------------------------------------------------------------------

    /** Classification metrics: [accuracy, precision, recall, F1]. */
    private static double[] classificationMetrics(boolean[] truth, boolean[] pred) {
        int tp=0, fp=0, tn=0, fn=0;
        for (int i = 0; i < truth.length; i++) {
            if ( truth[i] &&  pred[i]) tp++;
            if (!truth[i] &&  pred[i]) fp++;
            if (!truth[i] && !pred[i]) tn++;
            if ( truth[i] && !pred[i]) fn++;
        }
        int n = truth.length;
        double acc  = n > 0 ? (double)(tp + tn) / n : 0;
        double prec = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double rec  = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1   = (prec + rec) > 0 ? 2.0 * prec * rec / (prec + rec) : 0;
        return new double[]{acc, prec, rec, f1};
    }

    /** Regression metrics: [MAE, RMSE]. */
    private static double[] regressionMetrics(double[] truth, double[] est) {
        int n = truth.length;
        double sumAbs = 0, sumSq = 0;
        for (int i = 0; i < n; i++) {
            double e = Math.abs(est[i] - truth[i]);
            sumAbs += e;
            sumSq  += e * e;
        }
        return new double[]{sumAbs / n, Math.sqrt(sumSq / n)};
    }

    private static double mean(double[] a) {
        double s = 0; for (double v : a) s += v; return s / a.length;
    }
    private static double std(double[] a) {
        double m = mean(a), s = 0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / a.length);
    }

    // avoid locale-specific formatting issues (e.g. comma vs dot)
    private static String fmt(double v)  { return String.format(java.util.Locale.ROOT, "%.4f", v); }
    private static String fmt6(double v) { return String.format(java.util.Locale.ROOT, "%.6f", v); }

    // private static String fmt(double v)  { return String.format("%.4f", v); }
    // private static String fmt6(double v) { return String.format("%.6f", v); }

    // -----------------------------------------------------------------------
    // CSV output
    // -----------------------------------------------------------------------
    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
        System.out.println("Wrote " + (rows.size() - 1) + " data rows → " + path);
    }
}
