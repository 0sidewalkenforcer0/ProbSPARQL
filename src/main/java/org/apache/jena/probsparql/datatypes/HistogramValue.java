package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a 1-D histogram distribution.
 *
 * Lexical form (JSON): {"B":50,"min":8.0,"max":12.0,"counts":[3,5,8,...]}
 *
 * <ul>
 *   <li>B      – number of equal-width bins</li>
 *   <li>min    – left edge of the first bin (inclusive)</li>
 *   <li>max    – right edge of the last bin (exclusive, except at boundary)</li>
 *   <li>counts – non-negative integer observation count per bin (length == B)</li>
 * </ul>
 */
public class HistogramValue implements Sampleable {

    private final int    B;
    private final double min;
    private final double max;
    private final int[]  counts;
    private final long   total;

    public HistogramValue(int B, double min, double max, int[] counts) {
        if (B <= 0)
            throw new IllegalArgumentException("B must be positive, got: " + B);
        if (min >= max)
            throw new IllegalArgumentException("min must be < max, got min=" + min + " max=" + max);
        if (counts == null || counts.length != B)
            throw new IllegalArgumentException("counts must have length B=" + B);

        long sum = 0;
        for (int c : counts) {
            if (c < 0)
                throw new IllegalArgumentException("counts must be non-negative, got: " + c);
            sum += c;
        }
        this.B      = B;
        this.min    = min;
        this.max    = max;
        this.counts = counts.clone();
        this.total  = sum;
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public int    getB()      { return B; }
    public double getMin()    { return min; }
    public double getMax()    { return max; }
    public long   getTotal()  { return total; }

    /** Returns a defensive copy. */
    public int[] getCounts() { return counts.clone(); }

    /** Width of a single bin: (max - min) / B */
    public double binWidth() { return (max - min) / B; }

    /** B+1 bin edge values. */
    public double[] edges() {
        double w = binWidth();
        double[] e = new double[B + 1];
        for (int i = 0; i <= B; i++) e[i] = min + i * w;
        return e;
    }

    /** B bin centre values. */
    public double[] binCenters() {
        double w = binWidth();
        double[] c = new double[B];
        for (int i = 0; i < B; i++) c[i] = min + (i + 0.5) * w;
        return c;
    }

    /**
     * Normalised probability mass per bin.
     * If total == 0 a uniform distribution is returned.
     */
    public double[] probabilities() {
        double[] p = new double[B];
        if (total == 0) {
            double u = 1.0 / B;
            Arrays.fill(p, u);
        } else {
            for (int i = 0; i < B; i++) p[i] = (double) counts[i] / total;
        }
        return p;
    }

    /**
     * CDF evaluated at {@code x} using linear interpolation within bins.
     *
     * @return cumulative probability in [0, 1]
     */
    public double cdf(double x) {
        if (x <= min) return 0.0;
        if (x >= max) return 1.0;
        double w = binWidth();
        int bin = (int) Math.floor((x - min) / w);
        if (bin >= B) bin = B - 1;

        long cumPrev = 0;
        for (int i = 0; i < bin; i++) cumPrev += counts[i];

        // Linear interpolation inside current bin
        double frac = (x - (min + bin * w)) / w;
        double cum  = cumPrev + counts[bin] * frac;
        return total == 0 ? 0.0 : cum / total;
    }

    /**
     * Expected value: sum of (bin_center * probability).
     */
    public double mean() {
        if (total == 0) return (min + max) / 2.0;
        double[] centers = binCenters();
        double sum = 0.0;
        for (int i = 0; i < B; i++) sum += centers[i] * counts[i];
        return sum / total;
    }

    /**
     * Check structural compatibility with another histogram
     * (same B, same min, same max within floating-point tolerance).
     */
    public boolean isCompatible(HistogramValue other) {
        if (this.B != other.B) return false;
        double eps = 1e-9 * (max - min);
        return Math.abs(this.min - other.min) <= eps
            && Math.abs(this.max - other.max) <= eps;
    }

    // -----------------------------------------------------------------------
    // Object overrides
    // -----------------------------------------------------------------------

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistogramValue)) return false;
        HistogramValue h = (HistogramValue) o;
        return B == h.B
            && Double.compare(min, h.min) == 0
            && Double.compare(max, h.max) == 0
            && Arrays.equals(counts, h.counts);
    }

    @Override
    public int hashCode() {
        return Objects.hash(B, min, max, Arrays.hashCode(counts));
    }

    // -----------------------------------------------------------------------
    // Sampleable implementation
    // -----------------------------------------------------------------------

    /**
     * Draw n samples from this histogram: select bin with probability proportional
     * to counts, then draw uniformly within that bin.
     */
    @Override
    public double[][] sample(int n) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[] probs = probabilities();
        double bw = binWidth();
        double[][] samples = new double[n][1];
        for (int s = 0; s < n; s++) {
            // Multinomial bin selection
            double u = rng.nextDouble();
            double cum = 0.0;
            int bin = B - 1;
            for (int i = 0; i < B; i++) {
                cum += probs[i];
                if (u <= cum) { bin = i; break; }
            }
            // Uniform within bin
            double lo = min + bin * bw;
            samples[s][0] = lo + rng.nextDouble() * bw;
        }
        return samples;
    }

    /**
     * Log piecewise-constant density: log(p_bin / bin_width).
     * Returns NEGATIVE_INFINITY for points outside [min, max].
     */
    @Override
    public double logPdf(double[] x) {
        double v = x[0];
        if (v < min || v >= max) return Double.NEGATIVE_INFINITY;
        double bw = binWidth();
        int bin = Math.min((int) ((v - min) / bw), B - 1);
        if (total == 0) return Math.log(1.0 / (B * bw));
        double p = (double) counts[bin] / total;
        if (p <= 0.0) return Double.NEGATIVE_INFINITY;
        return Math.log(p / bw);
    }

    // -----------------------------------------------------------------------

    /** Round-trip serialisable JSON. */
    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"B\":").append(B);
        sb.append(",\"min\":").append(min);
        sb.append(",\"max\":").append(max);
        sb.append(",\"counts\":[");
        for (int i = 0; i < B; i++) {
            if (i > 0) sb.append(',');
            sb.append(counts[i]);
        }
        sb.append("]}");
        return sb.toString();
    }
}
