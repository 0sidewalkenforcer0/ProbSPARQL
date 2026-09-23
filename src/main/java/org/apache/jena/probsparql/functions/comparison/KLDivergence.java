package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramOperations;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.probsparql.functions.DistributionSeeds;
import org.apache.jena.probsparql.functions.DistributionSupport;
import org.apache.jena.probsparql.utils.MatrixUtils;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the Kullback-Leibler (KL) divergence between supported distributions.
 * 
 * <p>KL divergence measures how one probability distribution diverges from another.
 * It is asymmetric: D_KL(P||Q) ≠ D_KL(Q||P)</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?divergence WHERE {
 *   ?var1 uq:hasDistribution ?dist1 .
 *   ?var2 uq:hasDistribution ?dist2 .
 *   BIND(prob:kldivergence(?dist1, ?dist2) AS ?divergence)
 * }
 * </pre>
 * 
 * <p>GMM uses the original Monte Carlo estimator. Same-grid Histogram uses exact
 * discrete KL over cell masses. Dirichlet-Dirichlet uses the closed-form KL.
 * Cross-type Sampleable distributions fall back to sampling from the first
 * argument and evaluating both log densities.</p>
 * 
 * @author ProbSPARQL Team
 */
public class KLDivergence extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#kldivergence";
    
    private static final int DEFAULT_SAMPLES = 10000;

    /**
     * KL divergence is asymmetric, so the random stream is seeded from the ordered
     * operand pair. A single shared {@code Random} instance would advance between
     * calls and make repeated evaluation of the same expression return different
     * values; see {@link DistributionSeeds}.
     */
    private static java.util.Random rngFor(Object p, Object q) {
        return DistributionSeeds.rngForOrderedPair(p, q);
    }


    /**
     * Compute KL divergence D_KL(dist1 || dist2).
     * 
     * @param dist1Node First distribution (P in D_KL(P||Q))
     * @param dist2Node Second distribution (Q in D_KL(P||Q))
     * @return KL divergence value (non-negative)
     */
    @Override
    public NodeValue exec(NodeValue dist1Node, NodeValue dist2Node) {
        Object value1 = dist1Node.asNode().getLiteralValue();
        Object value2 = dist2Node.asNode().getLiteralValue();

        if (value1 instanceof HistogramValue hist1 && value2 instanceof HistogramValue hist2) {
            return NodeValue.makeDouble(HistogramOperations.klDivergence(hist1, hist2));
        }

        if (value1 instanceof DirichletValue dir1 && value2 instanceof DirichletValue dir2) {
            return NodeValue.makeDouble(dirichletKL(dir1, dir2));
        }

        if (value1 instanceof Sampleable sampleable1 && value2 instanceof Sampleable sampleable2
                && !(value1 instanceof GMMValue && value2 instanceof GMMValue)) {
            if (DistributionSupport.dimensions(sampleable1) != DistributionSupport.dimensions(sampleable2)) {
                throw new IllegalArgumentException(
                    "Distributions must have same dimensionality. Got d1="
                        + DistributionSupport.dimensions(sampleable1)
                        + ", d2=" + DistributionSupport.dimensions(sampleable2));
            }
            return NodeValue.makeDouble(sampleBasedKL(sampleable1, sampleable2, DEFAULT_SAMPLES));
        }

        GMMValue gmm1 = extractGMM(dist1Node, "first");
        GMMValue gmm2 = extractGMM(dist2Node, "second");
        // Validate compatibility
        if (gmm1.getDimensions() != gmm2.getDimensions()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality. Got d1=" + gmm1.getDimensions() +
                ", d2=" + gmm2.getDimensions());
        }
        
        double kl = computeKLDivergence(gmm1, gmm2, DEFAULT_SAMPLES);
        
        return NodeValue.makeDouble(kl);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Compute KL divergence using Monte Carlo approximation.
     * 
     * D_KL(P||Q) ≈ (1/N) Σ [log(p(x_i)) - log(q(x_i))] where x_i ~ P
     */
    private double computeKLDivergence(GMMValue p, GMMValue q, int numSamples) {
        java.util.Random random = rngFor(p, q);
        double sum = 0.0;
        
        for (int i = 0; i < numSamples; i++) {
            // Sample from P
            double[] sample = sampleFromGMM(p, random);
            
            // Compute log(p(x))
            double logP = computeLogPDF(p, sample);
            
            // Compute log(q(x))
            double logQ = computeLogPDF(q, sample);
            
            // Accumulate difference
            sum += (logP - logQ);
        }
        
        return sum / numSamples;
    }

    private double sampleBasedKL(Sampleable p, Sampleable q, int numSamples) {
        double[][] samples = p.sample(numSamples, rngFor(p, q));
        double sum = 0.0;
        for (double[] sample : samples) {
            double logP = p.logPdf(sample);
            double logQ = q.logPdf(sample);
            if (Double.isInfinite(logP)) {
                continue;
            }
            if (Double.isInfinite(logQ)) {
                return Double.POSITIVE_INFINITY;
            }
            sum += logP - logQ;
        }
        return Math.max(0.0, sum / numSamples);
    }

    private double dirichletKL(DirichletValue p, DirichletValue q) {
        double[] alpha = p.getAlphas();
        double[] beta = q.getAlphas();
        if (alpha.length != beta.length) {
            throw new IllegalArgumentException(
                "Dirichlet dimensions must match. Got d1=" + alpha.length + ", d2=" + beta.length);
        }
        double alphaSum = p.getAlphasSum();
        double betaSum = q.getAlphasSum();
        double kl = logBeta(beta, betaSum) - logBeta(alpha, alphaSum);
        double digammaAlphaSum = digamma(alphaSum);
        for (int i = 0; i < alpha.length; i++) {
            kl += (alpha[i] - beta[i]) * (digamma(alpha[i]) - digammaAlphaSum);
        }
        return Math.max(0.0, kl);
    }

    private double logBeta(double[] values, double sum) {
        double out = 0.0;
        for (double value : values) {
            out += logGamma(value);
        }
        return out - logGamma(sum);
    }

    private double digamma(double x) {
        double result = 0.0;
        while (x < 7.0) {
            result -= 1.0 / x;
            x += 1.0;
        }
        double inv = 1.0 / x;
        double inv2 = inv * inv;
        return result + Math.log(x) - 0.5 * inv - inv2 * (1.0 / 12.0 - inv2 * (1.0 / 120.0 - inv2 / 252.0));
    }

    private double logGamma(double a) {
        double[] c = {
            0.99999999999999709182,
            57.156235665862923517,
            -59.597960355475491248,
            14.136097974741747174,
            -0.49191381609762019978,
            0.33994649984811888699e-4,
            0.46523628927048575665e-4,
            -0.98374475304879564677e-4,
            0.15808870322491248884e-3,
            -0.21026444172410488319e-3,
            0.21743961811521264320e-3,
            -0.16431810653676389022e-3,
            0.84418223983852743293e-4,
            -0.26190838401581408670e-4,
            0.36899182659531622704e-5
        };
        double x = c[0];
        for (int i = 1; i < c.length; i++) {
            x += c[i] / (a + i);
        }
        double t = a + 607.0 / 128.0 + 0.5;
        return 0.5 * Math.log(2.0 * Math.PI) + (a + 0.5) * Math.log(t) - t + Math.log(x / a);
    }
    
    /**
     * Sample a point from a GMM.
     * 
     * 1. Select component k with probability w_k
     * 2. Sample from N(μ_k, Σ_k)
     */
    private double[] sampleFromGMM(GMMValue gmm, java.util.Random random) {
        int d = gmm.getDimensions();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Select component
        int component = sampleCategorical(weights, random);
        
        // Sample from selected Gaussian
        return sampleGaussian(means[component], covariances[component], covType, d, random);
    }
    
    /**
     * Sample from categorical distribution.
     */
    private int sampleCategorical(double[] weights, java.util.Random random) {
        double u = random.nextDouble();
        double cumulative = 0.0;
        
        for (int k = 0; k < weights.length; k++) {
            cumulative += weights[k];
            if (u <= cumulative) {
                return k;
            }
        }
        
        return weights.length - 1;
    }
    
    /**
     * Sample from multivariate Gaussian using Cholesky decomposition.
     */
    private double[] sampleGaussian(double[] mean, double[][] covariance, 
                                    String covType, int d, java.util.Random random) {
        double[] sample = new double[d];
        
        if (d == 1) {
            // Optimized 1D case
            double stddev = Math.sqrt(getVariance(covariance, covType));
            sample[0] = mean[0] + stddev * random.nextGaussian();
        } else {
            // Multi-dimensional: sample = mean + L * z
            // where z ~ N(0, I) and L is Cholesky decomposition of covariance
            double[][] fullCov = toFullCovariance(covariance, covType, d);
            double[][] L = MatrixUtils.choleskyDecomposition(fullCov, d);
            
            // Generate standard normal samples
            double[] z = new double[d];
            for (int i = 0; i < d; i++) {
                z[i] = random.nextGaussian();
            }
            
            // Compute L * z + mean
            double[] Lz = MatrixUtils.matrixVectorMultiply(L, z, d);
            for (int i = 0; i < d; i++) {
                sample[i] = mean[i] + Lz[i];
            }
        }
        
        return sample;
    }
    
    /**
     * Get variance from covariance matrix.
     */
    private double getVariance(double[][] covariance, String covType) {
        switch (covType) {
            case "full":
            case "diag":
            case "spherical":
                return covariance[0][0];
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
    
    /**
     * Compute log PDF of GMM at a point.
     */
    private double computeLogPDF(GMMValue gmm, double[] point) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        double[] logComponents = new double[K];
        
        for (int k = 0; k < K; k++) {
            double logWeight = Math.log(weights[k]);
            double logGaussian = evaluateLogGaussian(
                point, means[k], covariances[k], covType, d
            );
            logComponents[k] = logWeight + logGaussian;
        }
        
        return logSumExp(logComponents);
    }
    
    /**
     * Evaluate log of Gaussian density.
     */
    private double evaluateLogGaussian(double[] x, double[] mean, 
                                       double[][] covariance, 
                                       String covType, int d) {
        double[] diff = MatrixUtils.subtract(x, mean, d);
        
        double logDet;
        double mahalanobis;
        
        if (d == 1) {
            // Optimized 1D case
            double variance = getVariance(covariance, covType);
            logDet = Math.log(variance);
            mahalanobis = (diff[0] * diff[0]) / variance;
        } else {
            // Multi-dimensional case
            double[][] fullCov = toFullCovariance(covariance, covType, d);
            double det = MatrixUtils.determinant(fullCov, d);
            logDet = Math.log(det);
            
            double[][] covInv = MatrixUtils.invertMatrix(fullCov, d);
            mahalanobis = MatrixUtils.quadraticForm(diff, covInv, d);
        }
        
        double logNormalization = -0.5 * d * Math.log(2 * Math.PI) - 0.5 * logDet;
        double logExponent = -0.5 * mahalanobis;
        
        return logNormalization + logExponent;
    }
    
    /**
     * Convert covariance to full matrix form.
     */
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        switch (type) {
            case "full":
                return cov;
                
            case "diag":
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? cov[0][i] : 0.0;
                    }
                }
                return full;
                
            case "spherical":
                double variance = cov[0][0];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? variance : 0.0;
                    }
                }
                return full;
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + type);
        }
    }
    
    /**
     * Log-sum-exp trick for numerical stability.
     */
    private double logSumExp(double[] logValues) {
        double maxLog = logValues[0];
        for (int i = 1; i < logValues.length; i++) {
            if (logValues[i] > maxLog) {
                maxLog = logValues[i];
            }
        }
        
        if (Double.isInfinite(maxLog) && maxLog < 0) {
            return Double.NEGATIVE_INFINITY;
        }
        
        double sum = 0.0;
        for (double logValue : logValues) {
            sum += Math.exp(logValue - maxLog);
        }
        
        return maxLog + Math.log(sum);
    }
}
