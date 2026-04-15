package org.apache.jena.probsparql;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.StmtIterator;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.functions.comparison.*;

import java.io.*;
import java.util.*;

/**
 * Exp 3.2: Similarity Join — Convergence Analysis (all methods)
 *
 * Fixes one "hard" pair of GMMs close to θ=0.3 and measures how quickly
 * each sampling method's JSD estimate converges to the 10k-sample reference.
 *
 * Methods: V1_MC, V2_STRATIFIED, V3_SPRT, V4_BOUNDS, V5_ADAPTIVE
 * Sample counts: 100, 500, 1 000, 5 000, 10 000, 50 000, 100 000
 * Repetitions per (method × sample_count): 50
 *
 * Note: for V3_SPRT and V4_BOUNDS, {@code samples} is the maximum budget;
 * those methods may terminate early, so the plot also captures average actual
 * samples used.
 *
 * Output: benchmark/results/exp3_2_convergence_multimethod.csv
 *   columns: Method, Samples, Iteration, EstJSD, AbsError, Time_ms
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.MultiMethodConvergenceBenchmark"
 */
public class MultiMethodConvergenceBenchmark {

    // -----------------------------------------------------------------------
    // Experiment configuration
    // -----------------------------------------------------------------------
    private static int[] sampleCounts = {100, 500, 1_000, 5_000, 10_000, 50_000, 100_000};
    private static int   repetitions  = 50;
    private static final int   GT_SAMPLES    = 100_000;  // reference
    private static final String DATASET      = "hard";

    private static final String[] METHODS = {
        "V1_MC", "V2_STRATIFIED", "V3_SPRT", "V4_BOUNDS", "V5_ADAPTIVE"
    };

    // -----------------------------------------------------------------------

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String dataDir   = "benchmark/data";
        String outputDir = "benchmark/results";

        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--data-dir"))         dataDir       = args[++i];
            else if (args[i].equals("--output-dir"))  outputDir     = args[++i];
            else if (args[i].equals("--repetitions")) repetitions   = Integer.parseInt(args[++i]);
            else if (args[i].equals("--max-samples")) {
                int maxN = Integer.parseInt(args[++i]);
                sampleCounts = Arrays.stream(sampleCounts).filter(n -> n <= maxN).toArray();
                if (sampleCounts.length == 0) sampleCounts = new int[]{maxN};
            }
        }
        new File(outputDir).mkdirs();

        String ttlPath = dataDir + "/simjoin_" + DATASET + ".ttl";
        System.out.println("=== Exp 3.2: Convergence Analysis (all methods) ===");
        System.out.println("Dataset  : " + DATASET);
        System.out.println("Methods  : " + Arrays.toString(METHODS));
        System.out.println("Samples  : " + Arrays.toString(sampleCounts));
        System.out.println("GT ref   : " + GT_SAMPLES + " samples");
        System.out.println("Repeats  : " + repetitions);
        System.out.println();

        // Load model
        Model model = ModelFactory.createDefaultModel();
        try (InputStream in = new FileInputStream(ttlPath)) {
            model.read(in, null, "TTL");
        }

        List<GMMValue> leftGMMs  = new ArrayList<>();
        List<GMMValue> rightGMMs = new ArrayList<>();
        loadGMMsAligned(model, leftGMMs, rightGMMs);
        int nAligned = Math.min(leftGMMs.size(), rightGMMs.size());
        System.out.printf("Loaded %d aligned pairs%n%n", nAligned);

        // Pick the aligned pair whose quick-estimate JSD is closest to θ=0.3
        GMMValue g1ref = null, g2ref = null;
        double closestDist = Double.MAX_VALUE;
        StratifiedSampler referenceSampler = new StratifiedSampler(0);
        int QUICK_SAMPLES = 2_000; // fast screen, not full GT_SAMPLES
        for (int i = 0; i < nAligned; i++) {
            GMMValue g1 = leftGMMs.get(i);
            GMMValue g2 = rightGMMs.get(i);
            if (g1.getDimensions() != g2.getDimensions()) continue;
            double jsd = referenceSampler.computeJSD(g1, g2, QUICK_SAMPLES);
            double dist = Math.abs(jsd - 0.3);
            if (dist < closestDist) {
                closestDist = dist; g1ref = g1; g2ref = g2;
            }
        }

        if (g1ref == null) {
            System.err.println("ERROR: No compatible GMM pairs found"); return;
        }

        // Ground-truth JSD (high-precision reference)
        double gtJSD = referenceSampler.computeJSD(g1ref, g2ref, GT_SAMPLES);
        System.out.printf("Selected pair: GT JSD = %.6f (closest to θ=0.3, dist=%.6f)%n%n",
            gtJSD, closestDist);

        // ----------------------------------------------------------------
        // Initialize samplers
        // ----------------------------------------------------------------
        SPRTSampler        sprt     = new SPRTSampler(42,
                JSDivergenceConfig.SPRT_ALPHA,
                JSDivergenceConfig.SPRT_BETA,
                JSDivergenceConfig.SPRT_EPSILON);
        BoundsFilterSampler bounds  = new BoundsFilterSampler(JSDivergenceConfig.SPRT_EPSILON);
        AdaptiveSampler    adaptive = new AdaptiveSampler(JSDivergenceConfig.SPRT_EPSILON,
                JSDivergenceConfig.SPRT_ALPHA,
                JSDivergenceConfig.SPRT_BETA,
                JSDivergenceConfig.SPRT_EPSILON);

        // ----------------------------------------------------------------
        // Run convergence experiments
        // ----------------------------------------------------------------
        String outCsv = outputDir + "/exp3_2_convergence_multimethod.csv";
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Method", "Samples", "Iteration", "EstJSD", "AbsError", "Time_ms"});

        for (String method : METHODS) {
            System.out.println("--- Method: " + method + " ---");
            for (int samples : sampleCounts) {
                double sumJSD = 0, sumErr = 0, sumTime = 0;
                for (int it = 0; it < repetitions; it++) {
                    // Re-seed per iteration for reproducibility
                    StratifiedSampler strat = new StratifiedSampler(it);

                    long t0 = System.nanoTime();
                    double est = computeJSD(g1ref, g2ref, method, samples,
                            strat, sprt, bounds, adaptive, it);
                    double timeMs = (System.nanoTime() - t0) / 1_000_000.0;

                    double absErr = Math.abs(est - gtJSD);
                    sumJSD  += est;
                    sumErr  += absErr;
                    sumTime += timeMs;

                    rows.add(new String[]{
                        method,
                        String.valueOf(samples),
                        String.valueOf(it + 1),
                        String.format("%.6f", est),
                        String.format("%.6f", absErr),
                        String.format("%.3f", timeMs)
                    });
                }
                System.out.printf("  N=%7d  meanJSD=%.6f  meanErr=%.6f  avgTime=%.2fms%n",
                    samples,
                    sumJSD  / repetitions,
                    sumErr  / repetitions,
                    sumTime / repetitions);
            }
            System.out.println();
        }

        model.close();
        writeCsv(outCsv, rows);
    }

    // -----------------------------------------------------------------------
    // JSD dispatch
    // -----------------------------------------------------------------------
    private static double computeJSD(GMMValue g1, GMMValue g2, String method,
            int samples,
            StratifiedSampler strat,
            SPRTSampler sprt,
            BoundsFilterSampler bounds,
            AdaptiveSampler adaptive,
            long seed) {
        switch (method) {
            case "V1_MC":         return computeMC(g1, g2, samples, new Random(seed));
            case "V2_STRATIFIED": return strat.computeJSD(g1, g2, samples);
            case "V3_SPRT":       return sprt.computeJSD(g1, g2, samples);
            case "V4_BOUNDS":     return bounds.computeJSD(g1, g2, samples);
            case "V5_ADAPTIVE":   return adaptive.computeJSD(g1, g2, samples);
            default:              return computeMC(g1, g2, samples, new Random(seed));
        }
    }

    /** Plain Monte Carlo JSD (V1_MC). */
    private static double computeMC(GMMValue p, GMMValue q, int n, Random rng) {
        int kP = p.getNComponents(), kQ = q.getNComponents(), kM = kP + kQ, d = p.getDimensions();
        double[] wM = new double[kM];
        double[][] meansM = new double[kM][];
        double[][][] covsM = new double[kM][][];
        for (int k = 0; k < kP; k++) {
            wM[k]        = 0.5 * p.getWeights()[k];
            meansM[k]    = p.getMeans()[k];
            covsM[k]     = p.getCovariances()[k];
        }
        for (int k = 0; k < kQ; k++) {
            wM[kP+k]     = 0.5 * q.getWeights()[k];
            meansM[kP+k] = q.getMeans()[k];
            covsM[kP+k]  = q.getCovariances()[k];
        }
        GMMValue m = new GMMValue(kM, d, p.getCovarianceType(), wM, meansM, covsM);
        int half = n / 2;
        return Math.max(0, 0.5 * (klMC(p, m, half, rng) + klMC(q, m, half, rng)));
    }

    private static double klMC(GMMValue p, GMMValue q, int n, Random rng) {
        double sum = 0;
        for (int i = 0; i < n; i++) {
            double[] x   = p.sampleOne(rng);
            double   lp  = p.logPdf(x);
            double   lq  = q.logPdf(x);
            sum += lp - lq;
        }
        return sum / n;
    }

    // -----------------------------------------------------------------------
    // Model loading
    // -----------------------------------------------------------------------
    /**
     * Loads GMMs as aligned pairs: left_i ↔ right_i sorted by URI.
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
    // CSV output
    // -----------------------------------------------------------------------
    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
        System.out.println("Wrote " + (rows.size() - 1) + " rows → " + path);
    }
}
