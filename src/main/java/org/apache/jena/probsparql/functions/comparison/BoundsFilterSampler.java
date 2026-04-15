package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;

/**
 * V4: Bounds Filter Sampler (Jensen's Inequality)
 * 
 * Uses analytical bounds to quickly reject GMM pairs that are clearly different
 * without any sampling. This is the core "Filter" in "Filter-and-Refine".
 * 
 * Jensen's Inequality provides bounds:
 * - If the means are far apart, the JSD is guaranteed to be large
 * - If the variances are small, we can bound JSD from below
 * 
 * @author ProbSPARQL Team
 */
public class BoundsFilterSampler {
    private static final int DEFAULT_BOUND_BINS = 32;
    
    // Filter configuration
    private final double boundsThreshold;  // If JSD > this, reject immediately
    
    // Statistics
    private int totalPairs = 0;
    private int filteredByBounds = 0;
    private int requiredSampling = 0;
    
    public BoundsFilterSampler(double boundsThreshold) {
        this.boundsThreshold = boundsThreshold;
    }
    
    /**
     * Check if JSD is guaranteed to exceed threshold using analytical bounds.
     * Returns: {canFilter, estimatedJSD, isLowerBound}
     *   - canFilter: true if we can definitively reject (JSD > threshold)
     *   - estimatedJSD: point estimate using bounds
     *   - isLowerBound: true if estimatedJSD is a guaranteed lower bound
     */
    public double[] checkBounds(GMMValue p, GMMValue q) {
        totalPairs++;

        // Use a conservative lower bound derived from discretization (DPI).
        // The heuristic moment-based scores below are still available as helpers,
        // but they are not used for definitive filtering because they are not
        // guaranteed lower bounds.
        double lowerBound = computeDiscretizedJSD(p, q, DEFAULT_BOUND_BINS);
        
        if (lowerBound > boundsThreshold) {
            // Guaranteed to exceed threshold - filter out
            filteredByBounds++;
            return new double[] {1.0, lowerBound, 1.0};  // {filter, jsd, isLowerBound}
        }
        
        // Cannot definitively filter - need sampling
        requiredSampling++;
        return new double[] {0.0, lowerBound, 1.0};  // {noFilter, jsd, isLowerBound}
    }
    
    /**
     * Compute lower bound on JSD based on mean distance.
     * 
     * Using Pinsker's inequality and relationship between KL and mean distance:
     * JSD >= 0.5 * ||mean1 - mean2||^2 / (var1 + var2)
     */
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
     * Compute lower bound based on variance difference.
     */
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
     * By the Data Processing Inequality (DPI), for any deterministic function f:
     *   JSD(f(P) || f(Q)) &lt;= JSD(P || Q)
     *
     * We use f = histogram binning on the real line: partition [lo, hi] into
     * numBins equal-width intervals and assign each GMM's probability mass to bins
     * using the Gaussian CDF. This gives a computable discrete JSD that is always
     * &lt;= the true continuous JSD.
     *
     * Bin range: [min_mu - 4*sigma_max, max_mu + 4*sigma_max] covers virtually all
     * mass for typical GMMs.
     *
     * Complexity: O(numBins x K) ~= O(90) for numBins=30, K=3.
     *
     * @param g1      first GMM (must be 1D; returns 0.0 otherwise)
     * @param g2      second GMM
     * @param numBins number of histogram bins (30 recommended)
     * @return valid lower bound in [0, log(2)]
     */
    public double computeDiscretizedJSD(GMMValue g1, GMMValue g2, int numBins) {
        if (g1.getDimensions() != 1) return 0.0;

        // --- Compute support range covering both GMMs ---
        double lo = Double.MAX_VALUE;
        double hi = -Double.MAX_VALUE;

        double[][] means1 = g1.getMeans();
        double[][][] covs1 = g1.getCovariances();
        for (int i = 0; i < g1.getNComponents(); i++) {
            double mu  = means1[i][0];
            double sig = Math.sqrt(covs1[i][0][0]);
            lo = Math.min(lo, mu - 4.0 * sig);
            hi = Math.max(hi, mu + 4.0 * sig);
        }

        double[][] means2 = g2.getMeans();
        double[][][] covs2 = g2.getCovariances();
        for (int i = 0; i < g2.getNComponents(); i++) {
            double mu  = means2[i][0];
            double sig = Math.sqrt(covs2[i][0][0]);
            lo = Math.min(lo, mu - 4.0 * sig);
            hi = Math.max(hi, mu + 4.0 * sig);
        }

        if (hi - lo < 1e-12) return 0.0;

        // --- Compute histogram bin edges ---
        double binWidth = (hi - lo) / numBins;
        double[] edges = new double[numBins + 1];
        for (int b = 0; b <= numBins; b++) {
            edges[b] = lo + b * binWidth;
        }

        // --- Compute normalized bin probabilities for each GMM ---
        double[] p = binMass(g1, edges, numBins);
        double[] q = binMass(g2, edges, numBins);

        // --- Discrete JSD = 0.5 * KL(p||m) + 0.5 * KL(q||m), m = (p+q)/2 ---
        double jsd = 0.0;
        for (int b = 0; b < numBins; b++) {
            double pb = p[b];
            double qb = q[b];
            double mb = 0.5 * (pb + qb);
            if (mb < 1e-300) continue;
            if (pb > 1e-300) jsd += 0.5 * pb * Math.log(pb / mb);
            if (qb > 1e-300) jsd += 0.5 * qb * Math.log(qb / mb);
        }

        return Math.max(0.0, Math.min(jsd, Math.log(2.0)));
    }

    /**
     * Compute probability mass per bin for a GMM using Gaussian CDF differences.
     * Result is normalized to sum to 1.
     */
    private double[] binMass(GMMValue gmm, double[] edges, int numBins) {
        double[] mass    = new double[numBins];
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covs = gmm.getCovariances();

        for (int k = 0; k < gmm.getNComponents(); k++) {
            double mu  = means[k][0];
            double sig = Math.sqrt(covs[k][0][0]);
            double w   = weights[k];
            double prev = normCDF(edges[0], mu, sig);
            for (int b = 0; b < numBins; b++) {
                double curr = normCDF(edges[b + 1], mu, sig);
                mass[b] += w * (curr - prev);
                prev = curr;
            }
        }

        // Normalize to handle floating-point rounding
        double sum = 0.0;
        for (double v : mass) sum += v;
        if (sum > 1e-10) {
            for (int b = 0; b < numBins; b++) mass[b] /= sum;
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
     * Compute JSD using only the conservative lower-bound strategy.
     * Returns: {lowerBound, samplesUsed}
     *
     * This method intentionally does not fall back to sampling. It exists as a
     * pure bound-based baseline so that V4 can be evaluated independently from
     * the full adaptive pipeline used in V5.
     */
    public double[] computeJSDWithFilter(GMMValue p, GMMValue q, int defaultSamples) {
        double[] boundsResult = checkBounds(p, q);
        return new double[] {boundsResult[1], 0};
    }
    
    /**
     * Simple compute JSD (for compatibility).
     */
    public double computeJSD(GMMValue p, GMMValue q, int samples) {
        return computeJSDWithFilter(p, q, samples)[0];
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
