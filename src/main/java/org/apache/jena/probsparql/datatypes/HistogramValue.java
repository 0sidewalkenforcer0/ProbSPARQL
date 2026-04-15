package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a 1-D histogram distribution.
 *
 * Lexical form (JSON): {"bins":[0.0,10.0,20.0,30.0],"weights":[0.2,0.5,0.3]}
 *
 * <ul>
 *   <li>bins    – strictly increasing bin boundaries, length N+1</li>
 *   <li>weights – non-negative probability masses, length N, summing to 1</li>
 * </ul>
 */
public class HistogramValue implements Sampleable {

    private final double[] bins;
    private final double[] weights;

    public HistogramValue(double[] bins, double[] weights) {
        if (bins == null || bins.length < 2) {
            throw new IllegalArgumentException("bins must have length at least 2");
        }
        if (weights == null || weights.length != bins.length - 1) {
            throw new IllegalArgumentException("weights must have length bins.length - 1");
        }

        double prev = bins[0];
        for (int i = 1; i < bins.length; i++) {
            if (!(bins[i] > prev)) {
                throw new IllegalArgumentException("bins must be strictly increasing");
            }
            prev = bins[i];
        }

        double sum = 0.0;
        for (double w : weights) {
            if (w < 0.0) {
                throw new IllegalArgumentException("weights must be non-negative");
            }
            sum += w;
        }
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("weights must sum to 1.0, got: " + sum);
        }

        this.bins = bins.clone();
        this.weights = weights.clone();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public double[] getBins() { return bins.clone(); }
    public double[] getWeights() { return weights.clone(); }
    public int getBinCount() { return weights.length; }

    /** Returns the bin boundaries. */
    public double[] edges() { return getBins(); }

    /** Bin centre values. */
    public double[] binCenters() {
        double[] c = new double[getBinCount()];
        for (int i = 0; i < getBinCount(); i++) {
            c[i] = 0.5 * (bins[i] + bins[i + 1]);
        }
        return c;
    }

    /** Normalised probability mass per bin. */
    public double[] probabilities() { return getWeights(); }

    /**
     * CDF evaluated at {@code x} using linear interpolation within bins.
     *
     * @return cumulative probability in [0, 1]
     */
    public double cdf(double x) {
        if (x <= bins[0]) return 0.0;
        if (x >= bins[bins.length - 1]) return 1.0;

        double cumulative = 0.0;
        for (int i = 0; i < getBinCount(); i++) {
            double lo = bins[i];
            double hi = bins[i + 1];
            double mass = weights[i];
            if (x >= hi) {
                cumulative += mass;
                continue;
            }
            if (x > lo) {
                double frac = (x - lo) / (hi - lo);
                cumulative += mass * frac;
            }
            break;
        }
        return Math.max(0.0, Math.min(cumulative, 1.0));
    }

    /** Expected value: sum of (bin_center * probability). */
    public double mean() {
        double[] centers = binCenters();
        double sum = 0.0;
        for (int i = 0; i < getBinCount(); i++) sum += centers[i] * weights[i];
        return sum;
    }

    /**
     * Check structural compatibility with another histogram
     * (same bin boundaries within floating-point tolerance).
     */
    public boolean isCompatible(HistogramValue other) {
        if (this.getBinCount() != other.getBinCount()) return false;
        double scale = Math.max(1.0, Math.abs(bins[bins.length - 1] - bins[0]));
        double eps = 1e-9 * scale;
        for (int i = 0; i < bins.length; i++) {
            if (Math.abs(this.bins[i] - other.bins[i]) > eps) return false;
        }
        return true;
    }

    // -----------------------------------------------------------------------
    // Object overrides
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistogramValue)) return false;
        HistogramValue h = (HistogramValue) o;
        return Arrays.equals(bins, h.bins)
            && Arrays.equals(weights, h.weights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(bins), Arrays.hashCode(weights));
    }

    // -----------------------------------------------------------------------
    // Sampleable implementation
    // -----------------------------------------------------------------------

    /**
     * Draw n samples from this histogram: select bin with probability proportional
     * to weights, then draw uniformly within that bin.
     */
    @Override
    public double[][] sample(int n) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[][] samples = new double[n][1];
        for (int s = 0; s < n; s++) {
            double u = rng.nextDouble();
            double cum = 0.0;
            int bin = getBinCount() - 1;
            for (int i = 0; i < getBinCount(); i++) {
                cum += weights[i];
                if (u <= cum) { bin = i; break; }
            }
            double lo = bins[bin];
            double hi = bins[bin + 1];
            samples[s][0] = lo + rng.nextDouble() * (hi - lo);
        }
        return samples;
    }

    /**
     * Log piecewise-constant density: log(weight_bin / bin_width).
     * Returns NEGATIVE_INFINITY for points outside the support interval.
     */
    @Override
    public double logPdf(double[] x) {
        double v = x[0];
        if (v < bins[0] || v >= bins[bins.length - 1]) return Double.NEGATIVE_INFINITY;
        for (int i = 0; i < getBinCount(); i++) {
            double lo = bins[i], hi = bins[i + 1];
            if (v >= lo && v < hi) {
                double width = hi - lo;
                double p = weights[i];
                if (p <= 0.0) return Double.NEGATIVE_INFINITY;
                return Math.log(p / width);
            }
        }
        return Double.NEGATIVE_INFINITY;
    }

    // -----------------------------------------------------------------------

    /** Round-trip serialisable JSON. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"bins\":[");
        for (int i = 0; i < bins.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(bins[i]);
        }
        sb.append("],\"weights\":[");
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(weights[i]);
        }
        sb.append("]}");
        return sb.toString();
    }
}
