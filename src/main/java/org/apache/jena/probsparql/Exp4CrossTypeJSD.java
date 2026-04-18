package org.apache.jena.probsparql;

import org.apache.jena.probsparql.datatypes.*;
import org.apache.jena.probsparql.functions.comparison.PolyJSD;
import org.apache.jena.query.*;
import org.apache.jena.rdf.model.*;

import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exp 4.3 — Cross-Type JSD Accuracy
 *
 * <p>Tests whether the sample-based cross-type JSD fallback (PolyJSD) agrees
 * with optimised same-type baselines when comparing distributions that
 * represent the same underlying distribution in different formats.</p>
 *
 * <p>Pairs:
 * <ul>
 *   <li>GMM ↔ Hist  (100 pairs): base GMM → histogram from same GMM;
 *       baseline = Hist↔Hist JSD between two independent histograms of same GMM</li>
 *   <li>GMM ↔ GMM   (100 pairs): reference; cross-type fallback = same-type path</li>
 *   <li>Dir ↔ Dir   (100 pairs): Dirichlet same-type (MC) reference</li>
 *   <li>Hist ↔ Hist (100 pairs): Histogram same-type (exact) reference</li>
 * </ul>
 * For cross-type, "ground truth" = same-type JSD on histograms derived from the
 * same GMM (should be close to 0 for identical distributions).
 *
 * <p>Metrics: Pearson r, mean absolute error, time per pair.
 *
 * <p>Output CSV: {@code exp4_crosstype.csv}
 * <pre>PairType,PairIdx,SameTypeJSD,CrossTypeJSD,AbsError,TimeSameMs,TimeCrossMs</pre>
 *
 * Usage:
 *   mvn exec:java -Dexec.mainClass="org.apache.jena.probsparql.Exp4CrossTypeJSD"
 */
public class Exp4CrossTypeJSD {

    private static int N_PAIRS   = 100;
    private static int MC_SMALL  = 5_000;   // for quick same-type MC baselines
    private static int MC_CROSS  = 10_000;  // for cross-type fallback
    private static boolean DEMO_MODE = false;

    public static void main(String[] args) throws Exception {
        ProbSPARQL.init();

        String outputDir = "benchmark/results/exp4";
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output-dir")) outputDir = args[++i];
            if (args[i].equals("--demo"))       DEMO_MODE = true;
        }
        if (DEMO_MODE) {
            N_PAIRS = 5; MC_SMALL = 500; MC_CROSS = 1_000;
            System.out.println("  [DEMO MODE: N_PAIRS=5, MC_SMALL=500, MC_CROSS=1000]");
        }
        new File(outputDir).mkdirs();

        System.out.println("=== Exp 4.3: Cross-Type JSD Accuracy ===");

        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"PairType","PairIdx","SameTypeJSD","CrossTypeJSD",
                               "AbsError","TimeSameNs","TimeCrossNs"});

        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // 1. Hist ↔ Hist same-type (exact) reference
        System.out.println("\n── Hist↔Hist (same-type exact, baseline)");
        runHistHistPairs(N_PAIRS, 100, rng, rows);

        // 2. GMM ↔ GMM same-type MC
        System.out.println("\n── GMM↔GMM (same-type MC, baseline)");
        runGmmGmmPairs(N_PAIRS, 3, rng, rows);

        // 3. Dir ↔ Dir same-type MC
        System.out.println("\n── Dir↔Dir (same-type MC, baseline)");
        runDirDirPairs(N_PAIRS, 4, rng, rows);

        // 4. GMM ↔ Hist cross-type fallback
        System.out.println("\n── GMM↔Hist (cross-type fallback)");
        runGmmHistCrossPairs(N_PAIRS, 3, 100, rng, rows);

        // 5. Dir ↔ Hist cross-type fallback
        System.out.println("\n── Dir↔Hist (cross-type fallback)");
        runDirHistCrossPairs(N_PAIRS, 4, 100, rng, rows);

        writeCsv(outputDir + "/exp4_crosstype.csv", rows);
        System.out.println("\nResults → " + outputDir + "/exp4_crosstype.csv");
    }

    // -----------------------------------------------------------------------
    // Same-type: Hist↔Hist
    // -----------------------------------------------------------------------

    private static void runHistHistPairs(int n, int B, ThreadLocalRandom rng,
                                          List<String[]> rows) {
        double totalErr = 0;  int count = 0;
        for (int i = 1; i <= n; i++) {
            HistogramValue h1 = randHist(B, rng);
            HistogramValue h2 = new HistogramValue(h1.getBins(), randomWeights(B, rng));

            long t0 = System.nanoTime();
            double jsdSame  = org.apache.jena.probsparql.functions.comparison
                    .HistogramJSD.computeJSD(h1.probabilities(), h2.probabilities());
            long tSame = System.nanoTime() - t0;

            // "Cross" is really the same here (same-type reference)
            rows.add(new String[]{"Hist↔Hist", String.valueOf(i),
                    fmt(jsdSame), fmt(jsdSame), "0.000000",
                    String.valueOf(tSame), String.valueOf(tSame)});
            count++;
        }
        System.out.printf("  %d pairs, all exact (same-type)%n", count);
    }

    // -----------------------------------------------------------------------
    // Same-type: GMM↔GMM  (sample-based — same path as PolyJSD)
    // -----------------------------------------------------------------------

    private static void runGmmGmmPairs(int n, int k, ThreadLocalRandom rng,
                                        List<String[]> rows) {
        for (int i = 1; i <= n; i++) {
            GMMValue g1 = randGmm(k, rng);
            GMMValue g2 = randGmm(k, rng);

            long t0 = System.nanoTime();
            double jsdSame = PolyJSD.sampleBasedJSD(g1, g2, MC_SMALL);
            long tSame = System.nanoTime() - t0;

            rows.add(new String[]{"GMM↔GMM", String.valueOf(i),
                    fmt(jsdSame), fmt(jsdSame), "0.000000",
                    String.valueOf(tSame), String.valueOf(tSame)});
        }
        System.out.printf("  %d pairs done%n", n);
    }

    // -----------------------------------------------------------------------
    // Same-type: Dir↔Dir  (sample-based)
    // -----------------------------------------------------------------------

    private static void runDirDirPairs(int n, int k, ThreadLocalRandom rng,
                                        List<String[]> rows) {
        for (int i = 1; i <= n; i++) {
            DirichletValue d1 = randDir(k, rng);
            DirichletValue d2 = randDir(k, rng);

            long t0 = System.nanoTime();
            double jsdSame = PolyJSD.sampleBasedJSD(d1, d2, MC_SMALL);
            long tSame = System.nanoTime() - t0;

            rows.add(new String[]{"Dir↔Dir", String.valueOf(i),
                    fmt(jsdSame), fmt(jsdSame), "0.000000",
                    String.valueOf(tSame), String.valueOf(tSame)});
        }
        System.out.printf("  %d pairs done%n", n);
    }

    // -----------------------------------------------------------------------
    // Cross-type: GMM ↔ Hist
    //   same-type baseline: compare two histograms derived from same GMM
    //   cross-type:         compare GMM vs histogram derived from same GMM
    // -----------------------------------------------------------------------

    private static void runGmmHistCrossPairs(int n, int k, int B, ThreadLocalRandom rng,
                                              List<String[]> rows) {
        double sumAbsErr = 0; int count = 0;
        for (int i = 1; i <= n; i++) {
            GMMValue g = randGmm(k, rng);

            // Two histograms from same GMM (same-type baseline ≈ 0)
            HistogramValue h1 = histFromGmm(g, B, rng);
            HistogramValue h2 = histFromGmm(g, B, rng);

            // Make bins compatible
            HistogramValue h1c = h1;
            HistogramValue h2c = rebinHistogram(h2, h1, rng);

            long t0S = System.nanoTime();
            double jsdSame = org.apache.jena.probsparql.functions.comparison
                    .HistogramJSD.computeJSD(h1c.probabilities(), h2c.probabilities());
            long tSame = System.nanoTime() - t0S;

            // Cross-type: GMM vs h1
            long t0C = System.nanoTime();
            double jsdCross = PolyJSD.sampleBasedJSD(g, h1, MC_CROSS);
            long tCross = System.nanoTime() - t0C;

            double err = Math.abs(jsdCross - jsdSame);
            sumAbsErr += err;
            count++;

            rows.add(new String[]{"GMM↔Hist", String.valueOf(i),
                    fmt(jsdSame), fmt(jsdCross), fmt(err),
                    String.valueOf(tSame), String.valueOf(tCross)});
        }
        System.out.printf("  %d pairs  MAE=%.6f%n", count, sumAbsErr / count);
    }

    // -----------------------------------------------------------------------
    // Cross-type: Dir ↔ Hist (marginal dim 0 of Dirichlet)
    // -----------------------------------------------------------------------

    /**
     * 1D marginal of a Dirichlet along dimension 0.
     * The marginal P(X_0) for Dir(alpha) is Beta(alpha_0, alpha_0_bar)
     * where alpha_0_bar = sum(alpha) - alpha_0.
     */
    private static Sampleable dirMarginal1D(DirichletValue dir, ThreadLocalRandom rng) {
        double a0    = dir.getAlphas()[0];
        double aRest = dir.getAlphasSum() - a0;   // getAlphasSum() = sum(alphas)
        return new Sampleable() {
            // Gamma-based Beta sampling: X = G1/(G1+G2)
            @Override public double[][] sample(int n) {
                double[][] out = new double[n][1];
                for (int j = 0; j < n; j++) {
                    double g1 = sampleGamma(a0, rng);
                    double g2 = sampleGamma(aRest, rng);
                    out[j][0] = g1 / (g1 + g2);
                }
                return out;
            }
            @Override public double logPdf(double[] x) {
                double v = x[0];
                if (v <= 0 || v >= 1) return Double.NEGATIVE_INFINITY;
                return (a0 - 1) * Math.log(v) + (aRest - 1) * Math.log(1 - v)
                        - logBeta(a0, aRest);
            }
        };
    }

    /** Marsaglia-Tsang Gamma sampler. */
    private static double sampleGamma(double shape, ThreadLocalRandom rng) {
        if (shape < 1) {
            return sampleGamma(shape + 1, rng) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0, c = 1.0 / Math.sqrt(9 * d);
        while (true) {
            double x, v;
            do { x = rng.nextGaussian(); v = 1 + c * x; } while (v <= 0);
            v = v * v * v;
            double u = rng.nextDouble();
            if (u < 1 - 0.0331 * x * x * x * x) return d * v;
            if (Math.log(u) < 0.5 * x * x + d * (1 - v + Math.log(v))) return d * v;
        }
    }

    private static double logBeta(double a, double b) {
        return logGamma(a) + logGamma(b) - logGamma(a + b);
    }
    private static double logGamma(double x) {
        // Lanczos approximation (g=7, n=9)
        double[] c = {0.99999999999980993, 676.5203681218851, -1259.1392167224028,
                771.32342877765313, -176.61502916214059, 12.507343278686905,
                -0.13857109526572012, 9.9843695780195716e-6, 1.5056327351493116e-7};
        if (x < 0.5) return Math.log(Math.PI / Math.sin(Math.PI * x)) - logGamma(1 - x);
        x -= 1;
        double a = c[0]; double t = x + 7 + 0.5;
        for (int i = 1; i < 9; i++) a += c[i] / (x + i);
        return 0.5 * Math.log(2 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(a);
    }

    private static void runDirHistCrossPairs(int n, int k, int B, ThreadLocalRandom rng,
                                              List<String[]> rows) {
        double sumAbsErr = 0; int count = 0;
        for (int i = 1; i <= n; i++) {
            DirichletValue d = randDir(k, rng);

            // Two histograms from marginal dim 0 of the Dirichlet
            HistogramValue h1 = histFromDirMarginal(d, B, rng);
            HistogramValue h2 = histFromDirMarginal(d, B, rng);

            HistogramValue h1c = h1;
            HistogramValue h2c = rebinHistogram(h2, h1, rng);

            long t0S = System.nanoTime();
            double jsdSame = org.apache.jena.probsparql.functions.comparison
                    .HistogramJSD.computeJSD(h1c.probabilities(), h2c.probabilities());
            long tSame = System.nanoTime() - t0S;

            // Use 1D marginal of Dirichlet dim-0 (Beta distribution) as the Sampleable
            // so dimensions match the 1D histogram.
            Sampleable dirMarg = dirMarginal1D(d, rng);
            long t0C = System.nanoTime();
            double jsdCross = PolyJSD.sampleBasedJSD(dirMarg, h1, MC_CROSS);
            long tCross = System.nanoTime() - t0C;

            double err = Math.abs(jsdCross - jsdSame);
            sumAbsErr += err;
            count++;

            rows.add(new String[]{"Dir↔Hist", String.valueOf(i),
                    fmt(jsdSame), fmt(jsdCross), fmt(err),
                    String.valueOf(tSame), String.valueOf(tCross)});
        }
        System.out.printf("  %d pairs  MAE=%.6f%n", count, sumAbsErr / count);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static GMMValue randGmm(int k, ThreadLocalRandom rng) {
        double[] w = new double[k]; double ws = 0;
        for (int i = 0; i < k; i++) { w[i] = rng.nextDouble(0.1, 1.0); ws += w[i]; }
        for (int i = 0; i < k; i++) w[i] /= ws;

        double[][] means = new double[k][1];
        double[][][] covs = new double[k][1][1];
        for (int i = 0; i < k; i++) {
            means[i][0] = rng.nextDouble(12.0, 18.0);
            covs[i][0][0] = rng.nextDouble(0.01, 0.5);
        }
        return new GMMValue(k, 1, "full", w, means, covs);
    }

    private static DirichletValue randDir(int k, ThreadLocalRandom rng) {
        double[] alpha = new double[k];
        for (int i = 0; i < k; i++) alpha[i] = rng.nextDouble(0.5, 5.0);
        return new DirichletValue(alpha);
    }

    private static HistogramValue randHist(int B, ThreadLocalRandom rng) {
        double lo = rng.nextDouble(8.0, 11.0), hi = lo + rng.nextDouble(3.0, 8.0);
        double[] bins = equalWidthBins(lo, hi, B);
        return new HistogramValue(bins, randomWeights(B, rng));
    }

    private static double[] randomWeights(int B, ThreadLocalRandom rng) {
        double[] w = new double[B];
        double sum = 0.0;
        for (int i = 0; i < B; i++) {
            w[i] = rng.nextDouble(0.1, 1.0);
            sum += w[i];
        }
        for (int i = 0; i < B; i++) w[i] /= sum;
        return w;
    }

    /** Build a histogram of the first dimension of a GMM by MC sampling. */
    private static HistogramValue histFromGmm(GMMValue gmm, int B, ThreadLocalRandom rng) {
        double[][] samples = gmm.sample(10_000);
        double[] vals = new double[samples.length];
        for (int i = 0; i < samples.length; i++) vals[i] = samples[i][0];
        return buildHistogram(vals, B);
    }

    /** Build a histogram of marginal dimension 0 of a Dirichlet by MC sampling. */
    private static HistogramValue histFromDirMarginal(DirichletValue dir, int B, ThreadLocalRandom rng) {
        double[][] samples = dir.sample(10_000);
        double[] vals = new double[samples.length];
        for (int i = 0; i < samples.length; i++) vals[i] = samples[i][0];
        return buildHistogram(vals, B);
    }

    private static HistogramValue buildHistogram(double[] vals, int B) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double v : vals) { if (v < lo) lo = v; if (v > hi) hi = v; }
        double range = hi - lo;
        if (range < 1e-12) { range = 1.0; hi = lo + 1.0; }
        // add small buffer
        lo -= range * 0.05;  hi += range * 0.05;
        double[] bins = equalWidthBins(lo, hi, B);
        int[] counts = new int[B];
        for (double v : vals) {
            int bin = findBin(v, bins);
            if (bin < 0) bin = 0; if (bin >= B) bin = B - 1;
            counts[bin]++;
        }
        double total = vals.length;
        double[] weights = new double[B];
        for (int i = 0; i < B; i++) weights[i] = counts[i] / total;
        return new HistogramValue(bins, weights);
    }

    /** Resample h2 onto the bin grid of h1 using Monte Carlo rebinning. */
    private static HistogramValue rebinHistogram(HistogramValue h2, HistogramValue h1,
                                                 ThreadLocalRandom rng) {
        int B = h1.getBinCount();
        double[] targetBins = h1.getBins();
        double[][] samples = h2.sample(10_000);
        int[] counts = new int[B];
        for (double[] sample : samples) {
            int bin = findBin(sample[0], targetBins);
            if (bin < 0) bin = 0;
            if (bin >= B) bin = B - 1;
            counts[bin]++;
        }
        double[] weights = new double[B];
        for (int i = 0; i < B; i++) weights[i] = counts[i] / 10_000.0;
        return new HistogramValue(targetBins, weights);
    }

    private static double[] equalWidthBins(double lo, double hi, int B) {
        double[] bins = new double[B + 1];
        double width = (hi - lo) / B;
        for (int i = 0; i <= B; i++) bins[i] = lo + i * width;
        return bins;
    }

    private static int findBin(double v, double[] bins) {
        if (v <= bins[0]) return 0;
        if (v >= bins[bins.length - 1]) return bins.length - 2;
        for (int i = 0; i < bins.length - 1; i++) {
            if (v >= bins[i] && v < bins[i + 1]) return i;
        }
        return bins.length - 2;
    }

    private static String fmt(double v) { return String.format("%.6f", v); }

    private static void writeCsv(String path, List<String[]> rows) throws IOException {
        try (PrintWriter pw = new PrintWriter(new FileWriter(path))) {
            for (String[] row : rows) pw.println(String.join(",", row));
        }
        System.out.println("CSV written: " + path);
    }
}
