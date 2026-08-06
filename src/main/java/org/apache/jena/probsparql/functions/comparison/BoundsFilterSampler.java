package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;

/**
 * V4: Bounds Filter Sampler — the "Filter" in "Filter-and-Refine".
 *
 * <p>Computes a <em>guaranteed lower bound</em> on JSD(P‖Q) without sampling, so
 * that pairs whose bound already exceeds the decision threshold can be rejected
 * outright. The bound follows from the Data Processing Inequality (DPI): for any
 * deterministic map {@code f}, {@code JSD(f(P)‖f(Q)) <= JSD(P‖Q)}. Here {@code f}
 * is per-axis histogram binning of the exact Gaussian marginals.</p>
 *
 * <h2>Soundness contract</h2>
 * <p>The bound is only usable in one direction:</p>
 * <ul>
 *   <li>{@code bound > threshold} ⟹ {@code JSD > threshold}. Rejecting is sound.</li>
 *   <li>{@code bound <= threshold} says <em>nothing</em> about the true JSD, so the
 *       pair must be refined by an estimator before a decision can be made.</li>
 * </ul>
 * <p>Consequently {@link #computeJSDWithFilter} reports whether its verdict is
 * conclusive, and callers must refine when it is not. Treating an inconclusive
 * lower bound as a JSD estimate produces systematic false positives.</p>
 *
 * @author ProbSPARQL Team
 */
public class BoundsFilterSampler {
    private static final int DEFAULT_BOUND_BINS = 32;

    /** {@link #checkBounds} result index: 1.0 if the pair can be rejected outright. */
    public static final int CHECK_CAN_FILTER = 0;
    /** {@link #checkBounds} result index: the guaranteed JSD lower bound. */
    public static final int CHECK_BOUND = 1;
    /** {@link #checkBounds} result index: 1.0 if the verdict is conclusive, 0.0 if refinement is required. */
    public static final int CHECK_CONCLUSIVE = 2;

    /** {@link #computeJSDWithFilter} result index: the guaranteed JSD lower bound. */
    public static final int FILTER_BOUND = 0;
    /** {@link #computeJSDWithFilter} result index: samples consumed (always 0 — this stage never samples). */
    public static final int FILTER_SAMPLES = 1;
    /** {@link #computeJSDWithFilter} result index: 1.0 if conclusive, 0.0 if the caller must refine. */
    public static final int FILTER_CONCLUSIVE = 2;

    // Filter configuration
    private final double boundsThreshold;  // If the bound exceeds this, reject immediately

    // Statistics
    private int totalPairs = 0;
    private int filteredByBounds = 0;
    private int requiredSampling = 0;

    public BoundsFilterSampler(double boundsThreshold) {
        this.boundsThreshold = boundsThreshold;
    }

    /**
     * Evaluate the DPI lower bound and report whether it settles the decision.
     *
     * @return {@code {canFilter, lowerBound, isConclusive}} where {@code canFilter}
     *         and {@code isConclusive} are both 1.0 exactly when
     *         {@code lowerBound > threshold}, i.e. when the pair can be soundly
     *         rejected without sampling. When they are 0.0 the caller MUST refine:
     *         {@code lowerBound} is not an estimate of the true JSD.
     */
    public double[] checkBounds(GMMValue p, GMMValue q) {
        totalPairs++;

        // Guaranteed lower bound via discretization (DPI). The moment-based
        // heuristics below are NOT valid bounds and are never used for filtering.
        double lowerBound = computeDiscretizedJSD(p, q, DEFAULT_BOUND_BINS);

        if (lowerBound > boundsThreshold) {
            // Guaranteed to exceed threshold - filter out
            filteredByBounds++;
            return new double[] {1.0, lowerBound, 1.0};  // {filter, bound, conclusive}
        }

        // Cannot definitively filter - caller must refine by sampling
        requiredSampling++;
        return new double[] {0.0, lowerBound, 0.0};  // {noFilter, bound, inconclusive}
    }

    /**
     * Heuristic score based on mean distance. <strong>NOT a valid JSD bound.</strong>
     *
     * <p>Retained only for diagnostic comparison. For two Gaussians with equal
     * variance this expression is roughly twice the true JSD in the small-shift
     * regime, so using it to prune would incorrectly discard matching pairs.
     * It is deliberately not referenced by {@link #checkBounds}.</p>
     *
     * @deprecated not a lower bound; do not use for filtering
     */
    @Deprecated
    public double computeMeanDistanceBound(GMMValue p, GMMValue q) {
        if (p.getDimensions() != 1) {
            // Only optimize for 1D case
            return 0.0;
        }
        
        double[] meansP = componentMeans1D(p);
        double[] meansQ = componentMeans1D(q);
        
        // Use weighted mean of each GMM
        double meanP = weightedMean(meansP, p.getWeights());
        double meanQ = weightedMean(meansQ, q.getWeights());
        
        double meanDiff = Math.abs(meanP - meanQ);
        
        // Average variance across both distributions
        double varP = weightedVariance(meansP, p.getWeights(), p.getCovariances());
        double varQ = weightedVariance(meansQ, q.getWeights(), q.getCovariances());
        
        double combinedVar = varP + varQ;
        if (combinedVar < 1e-10) {
            // Degenerate case
            return meanDiff > 0 ? 1.0 : 0.0;
        }
        
        // Lower bound from mean distance
        double bound = 0.5 * (meanDiff * meanDiff) / combinedVar;
        
        // JSD is bounded by log(2) ≈ 0.693
        return Math.min(bound, Math.log(2));
    }
    
    /**
     * Heuristic score based on variance ratio. <strong>NOT a valid JSD bound.</strong>
     *
     * <p>{@code 0.5*log(varRatio)} is unbounded and can exceed {@code log 2}, the
     * maximum attainable JSD, so it would prune arbitrarily. Retained only for
     * diagnostic comparison and deliberately not referenced by {@link #checkBounds}.</p>
     *
     * @deprecated not a lower bound; do not use for filtering
     */
    @Deprecated
    public double computeVarianceBound(GMMValue p, GMMValue q) {
        if (p.getDimensions() != 1) {
            return 0.0;
        }
        
        double[] meansP = componentMeans1D(p);
        double[] meansQ = componentMeans1D(q);
        
        double varP = weightedVariance(meansP, p.getWeights(), p.getCovariances());
        double varQ = weightedVariance(meansQ, q.getWeights(), q.getCovariances());
        if (varP < 1e-12 || varQ < 1e-12) {
            return Math.abs(varP - varQ) > 1e-12 ? Math.log(2.0) : 0.0;
        }
        
        // If variances are very different, JSD is large
        double varRatio = Math.max(varP / varQ, varQ / varP);
        
        // Simplified bound: log of variance ratio
        if (varRatio > 1.0) {
            return 0.5 * Math.log(varRatio);
        }
        
        return 0.0;
    }
    
    private double weightedMean(double[] means, double[] weights) {
        double sum = 0.0;
        for (int i = 0; i < means.length; i++) {
            sum += weights[i] * means[i];
        }
        return sum;
    }
    
    private double weightedVariance(double[] means, double[] weights, double[][][] covariances) {
        // Compute weighted mean first
        double mean = weightedMean(means, weights);
        
        // Compute weighted variance
        double sum = 0.0;
        for (int i = 0; i < means.length; i++) {
            double var = covariances[i][0][0];  // 1D variance
            double diff = means[i] - mean;
            sum += weights[i] * (var + diff * diff);
        }
        return sum;
    }

    private double[] componentMeans1D(GMMValue gmm) {
        double[][] allMeans = gmm.getMeans();
        int k = gmm.getNComponents();
        double[] means = new double[k];
        for (int i = 0; i < k; i++) {
            means[i] = allMeans[i][0];
        }
        return means;
    }
    
    /**
     * Compute a valid lower bound on JSD(g1, g2) using discretized histogram binning.
     *
     * <p>By the Data Processing Inequality (DPI), for any deterministic function f:
     * {@code JSD(f(P) || f(Q)) <= JSD(P || Q)}.</p>
     *
     * <p>For each coordinate axis {@code j} we take {@code f_j(x) = } the index of the
     * bin containing {@code x_j}. That is a deterministic function of the sample, so
     * each axis yields a valid lower bound; the maximum over axes is therefore also a
     * valid lower bound and is the tightest one available from this family. The exact
     * axis marginal of a Gaussian component is {@code N(mu_j, Sigma_jj)}, so the bin
     * masses are computed in closed form from the normal CDF.</p>
     *
     * <p>The partition covers the whole real line: bin 0 is {@code (-inf, lo)} and the
     * last bin is {@code [hi, +inf)}. Retaining these overflow bins is what makes the
     * result a genuine DPI bound — renormalizing away the tail mass instead would
     * perturb P and Q by different amounts and void the guarantee.</p>
     *
     * <p>Complexity: O(d x numBins x K).</p>
     *
     * @param g1      first GMM
     * @param g2      second GMM (must have the same dimensionality)
     * @param numBins number of interior histogram bins per axis (30 recommended)
     * @return valid lower bound in [0, log(2)]; 0.0 if the bound is unavailable
     */
    public double computeDiscretizedJSD(GMMValue g1, GMMValue g2, int numBins) {
        int d = g1.getDimensions();
        if (d != g2.getDimensions()) {
            return 0.0;
        }
        double best = 0.0;
        for (int axis = 0; axis < d; axis++) {
            best = Math.max(best, axisDiscretizedJSD(g1, g2, axis, numBins));
        }
        return Math.max(0.0, Math.min(best, Math.log(2.0)));
    }

    /**
     * DPI lower bound obtained by binning a single coordinate axis.
     */
    private double axisDiscretizedJSD(GMMValue g1, GMMValue g2, int axis, int numBins) {
        double lo = Double.POSITIVE_INFINITY;
        double hi = Double.NEGATIVE_INFINITY;

        double[][] means1 = g1.getMeans();
        double[][][] covs1 = g1.getCovariances();
        String type1 = g1.getCovarianceType();
        for (int i = 0; i < g1.getNComponents(); i++) {
            double mu  = means1[i][axis];
            double sig = Math.sqrt(marginalVariance(covs1[i], type1, axis));
            lo = Math.min(lo, mu - 4.0 * sig);
            hi = Math.max(hi, mu + 4.0 * sig);
        }

        double[][] means2 = g2.getMeans();
        double[][][] covs2 = g2.getCovariances();
        String type2 = g2.getCovarianceType();
        for (int i = 0; i < g2.getNComponents(); i++) {
            double mu  = means2[i][axis];
            double sig = Math.sqrt(marginalVariance(covs2[i], type2, axis));
            lo = Math.min(lo, mu - 4.0 * sig);
            hi = Math.max(hi, mu + 4.0 * sig);
        }

        if (!(hi - lo > 1e-12)) return 0.0;

        double binWidth = (hi - lo) / numBins;
        double[] edges = new double[numBins + 1];
        for (int b = 0; b <= numBins; b++) {
            edges[b] = lo + b * binWidth;
        }

        // numBins interior cells plus two overflow cells => a partition of R
        double[] p = axisBinMass(g1, axis, edges, numBins);
        double[] q = axisBinMass(g2, axis, edges, numBins);

        // Discrete JSD = 0.5 * KL(p||m) + 0.5 * KL(q||m), m = (p+q)/2
        double jsd = 0.0;
        for (int b = 0; b < p.length; b++) {
            double pb = p[b];
            double qb = q[b];
            double mb = 0.5 * (pb + qb);
            if (mb < 1e-300) continue;
            if (pb > 1e-300) jsd += 0.5 * pb * Math.log(pb / mb);
            if (qb > 1e-300) jsd += 0.5 * qb * Math.log(qb / mb);
        }
        return jsd;
    }

    /**
     * Variance of the axis marginal of one Gaussian component, i.e. Sigma[axis][axis].
     */
    private static double marginalVariance(double[][] cov, String covType, int axis) {
        return switch (covType) {
            case "full" -> cov[axis][axis];
            case "diag" -> cov[0][axis];
            case "spherical" -> cov[0][0];
            default -> throw new IllegalStateException("Unknown covariance type: " + covType);
        };
    }

    /**
     * Probability mass of each cell of the axis partition, using exact Gaussian
     * marginal CDF differences.
     *
     * <p>The returned array has {@code numBins + 2} entries: index 0 holds
     * {@code P(X_axis < edges[0])}, indices {@code 1..numBins} hold the interior
     * bins, and the last entry holds {@code P(X_axis >= edges[numBins])}. Because
     * the cells partition the real line the masses already sum to 1, so no
     * renormalization is applied.</p>
     */
    private double[] axisBinMass(GMMValue gmm, int axis, double[] edges, int numBins) {
        double[] mass    = new double[numBins + 2];
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covs = gmm.getCovariances();
        String covType = gmm.getCovarianceType();

        for (int k = 0; k < gmm.getNComponents(); k++) {
            double mu  = means[k][axis];
            double sig = Math.sqrt(marginalVariance(covs[k], covType, axis));
            double w   = weights[k];
            double prev = normCDF(edges[0], mu, sig);
            mass[0] += w * prev;                       // underflow cell (-inf, lo)
            for (int b = 0; b < numBins; b++) {
                double curr = normCDF(edges[b + 1], mu, sig);
                mass[b + 1] += w * (curr - prev);
                prev = curr;
            }
            mass[numBins + 1] += w * (1.0 - prev);     // overflow cell [hi, +inf)
        }

        // Guard against tiny negative masses from erf approximation error.
        for (int b = 0; b < mass.length; b++) {
            if (mass[b] < 0.0) mass[b] = 0.0;
        }
        return mass;
    }

    /**
     * Normal CDF: Phi(x) = P(X &lt;= x) for X ~ N(mu, sigma^2).
     */
    private static double normCDF(double x, double mu, double sigma) {
        return 0.5 * (1.0 + erf((x - mu) / (sigma * Math.sqrt(2.0))));
    }

    /**
     * Error function approximation (Abramowitz &amp; Stegun 7.1.26).
     * Maximum absolute error: 1.5e-7.
     */
    private static double erf(double z) {
        double az = Math.abs(z);
        double t  = 1.0 / (1.0 + 0.3275911 * az);
        double poly = t * (0.254829592
                   + t * (-0.284496736
                   + t * (1.421413741
                   + t * (-1.453152027
                   + t * 1.061405429))));
        double result = 1.0 - poly * Math.exp(-az * az);
        return z >= 0 ? result : -result;
    }

    /**
     * Run the bound-only filter stage and report whether it is conclusive.
     *
     * <p>This method performs no sampling. It returns
     * {@code {lowerBound, samplesUsed=0, isConclusive}}; when {@code isConclusive}
     * is 0.0 the caller must refine with an estimator before deciding, because
     * {@code lowerBound} is a lower bound and not an estimate of the true JSD.
     * See {@link SimilarityEvaluator} for the V4 refine step.</p>
     */
    public double[] computeJSDWithFilter(GMMValue p, GMMValue q, int defaultSamples) {
        double[] boundsResult = checkBounds(p, q);
        return new double[] {boundsResult[CHECK_BOUND], 0, boundsResult[CHECK_CONCLUSIVE]};
    }

    /**
     * Returns the guaranteed JSD lower bound for this pair.
     *
     * <p>Renamed from the former {@code computeJSD} to make clear that the result is
     * a bound rather than a divergence estimate.</p>
     */
    public double computeLowerBound(GMMValue p, GMMValue q) {
        return checkBounds(p, q)[CHECK_BOUND];
    }
    
    // Getters for statistics
    public int getTotalPairs() { return totalPairs; }
    public int getFilteredByBounds() { return filteredByBounds; }
    public int getRequiredSampling() { return requiredSampling; }
    
    public double getFilterRate() {
        return totalPairs > 0 ? (double) filteredByBounds / totalPairs : 0.0;
    }
    
    public void resetStats() {
        totalPairs = 0;
        filteredByBounds = 0;
        requiredSampling = 0;
    }
}
