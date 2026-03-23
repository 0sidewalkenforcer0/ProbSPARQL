package org.apache.jena.probsparql.datatypes;

/**
 * Interface for probability distributions that support sampling and log-density evaluation.
 *
 * <p>Implementing this interface allows a distribution to participate in the
 * universal sample-based JSD fallback used by {@code prob:jsd} when comparing
 * distributions of different types.</p>
 *
 * <p>All distributions ({@link GMMValue}, {@link HistogramValue}, {@link DirichletValue})
 * implement this interface so that {@code PolyJSD} can apply the same cross-type
 * estimation algorithm regardless of the concrete distribution family.</p>
 */
public interface Sampleable {

    /**
     * Draw {@code n} independent samples from this distribution.
     *
     * @param n number of samples to draw, must be > 0
     * @return array of shape [n][d] where d is the dimensionality of the distribution;
     *         for 1-D distributions d=1 and each row holds a single value
     */
    double[][] sample(int n);

    /**
     * Evaluate the log-probability density (or log-probability mass) at point {@code x}.
     *
     * <p>For continuous distributions this is log p(x); for discrete distributions
     * (histograms) this is log(bin_count / total / bin_width), i.e. the log of the
     * piecewise-constant density.</p>
     *
     * @param x point at which to evaluate; length must match the distribution's dimensionality
     * @return log density at {@code x}, or {@code Double.NEGATIVE_INFINITY} if the point
     *         has zero density (e.g. outside histogram range)
     */
    double logPdf(double[] x);
}
