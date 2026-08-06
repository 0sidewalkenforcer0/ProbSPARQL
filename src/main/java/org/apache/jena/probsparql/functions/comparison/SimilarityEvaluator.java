package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;

import java.util.Random;

/**
 * Internal GMM similarity evaluator used by join operators and the legacy
 * {@code prob:jsdivergence} wrapper.
 *
 * <p>This evaluator is threshold-aware: for V3/V4/V5, the supplied
 * {@code decisionThreshold} is the threshold used by the sequential test,
 * bounds filter, and adaptive cascade. The returned {@code score} is therefore
 * a side product of a similarity decision pipeline rather than a promise of a
 * uniformly precise JSD estimator for every mode.</p>
 *
 * <p>For pure numerical JSD computation, {@code prob:jsd} remains the preferred
 * public interface.</p>
 */
public class SimilarityEvaluator {

    /**
     * When true, V4 reports the raw DPI lower bound even when it cannot settle the
     * decision, reproducing the historical bound-only baseline. Off by default
     * because an inconclusive lower bound used as a decision yields systematic
     * false positives. Enable with {@code -Dprobsparql.v4.boundsOnly=true}.
     */
    private static final boolean BOUNDS_ONLY =
        Boolean.getBoolean("probsparql.v4.boundsOnly");

    public enum Pathway {
        MC,
        STRATIFIED,
        SPRT,
        /** V4 decided by the analytic bound alone (conclusive reject, or bound-only mode). */
        BOUNDS,
        /** V4 bound was inconclusive, so the pair was refined by Monte Carlo. */
        BOUNDS_REFINED,
        ADAPTIVE_BOUNDS,
        ADAPTIVE_SPRT,
        ADAPTIVE_STRATIFIED
    }

    public static final class EvaluationResult {
        private final double score;
        private final int samplesUsed;
        private final Pathway pathway;

        EvaluationResult(double score, int samplesUsed, Pathway pathway) {
            this.score = score;
            this.samplesUsed = samplesUsed;
            this.pathway = pathway;
        }

        public double score() {
            return score;
        }

        public int samplesUsed() {
            return samplesUsed;
        }

        public Pathway pathway() {
            return pathway;
        }
    }

    private final StratifiedSampler stratifiedSampler;
    private final SPRTSampler sprtSampler;
    private final BoundsFilterSampler boundsSampler;
    private final AdaptiveSampler adaptiveSampler;
    private final String mode;
    private final double decisionThreshold;
    private final double alpha;
    private final double beta;

    public SimilarityEvaluator(double decisionThreshold) {
        this(decisionThreshold, JSDivergenceConfig.SPRT_ALPHA, JSDivergenceConfig.SPRT_BETA);
    }

    public SimilarityEvaluator(double decisionThreshold, double alpha, double beta) {
        this(System.getProperty("probsparql.mode", JSDivergenceConfig.MODE), decisionThreshold, alpha, beta);
    }

    public SimilarityEvaluator(String mode, double decisionThreshold, double alpha, double beta) {
        validateTailProbability("alpha", alpha);
        validateTailProbability("beta", beta);
        this.mode = mode;
        this.decisionThreshold = decisionThreshold;
        this.alpha = alpha;
        this.beta = beta;
        this.stratifiedSampler = new StratifiedSampler(42);
        this.sprtSampler = new SPRTSampler(42,
            alpha,
            beta,
            decisionThreshold);
        this.boundsSampler = new BoundsFilterSampler(decisionThreshold);
        this.adaptiveSampler = new AdaptiveSampler(
            decisionThreshold,
            alpha,
            beta,
            decisionThreshold);
    }

    public static SimilarityEvaluator legacy() {
        return new SimilarityEvaluator(
            JSDivergenceConfig.SPRT_EPSILON,
            JSDivergenceConfig.SPRT_ALPHA,
            JSDivergenceConfig.SPRT_BETA);
    }

    public double getDecisionThreshold() {
        return decisionThreshold;
    }

    public double getAlpha() {
        return alpha;
    }

    public double getBeta() {
        return beta;
    }

    private static void validateTailProbability(String label, double value) {
        if (!(value > 0.0 && value < 0.5)) {
            throw new IllegalArgumentException(
                label + " must be in the open interval (0, 0.5), got: " + value);
        }
    }

    public double evaluate(GMMValue gmm1, GMMValue gmm2) {
        return evaluateWithDetails(gmm1, gmm2).score();
    }

    public EvaluationResult evaluateWithDetails(GMMValue gmm1, GMMValue gmm2) {
        validateCompatibility(gmm1, gmm2);

        return switch (mode) {
            case JSDivergenceConfig.MODE_GT_100 ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.GT_100_SAMPLES),
                    JSDivergenceConfig.GT_100_SAMPLES, Pathway.MC);
            case JSDivergenceConfig.MODE_GT_1K ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.GT_1K_SAMPLES),
                    JSDivergenceConfig.GT_1K_SAMPLES, Pathway.MC);
            case JSDivergenceConfig.MODE_GT_5K ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.GT_5K_SAMPLES),
                    JSDivergenceConfig.GT_5K_SAMPLES, Pathway.MC);
            case JSDivergenceConfig.MODE_GT_10K ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.GT_10K_SAMPLES),
                    JSDivergenceConfig.GT_10K_SAMPLES, Pathway.MC);
            case JSDivergenceConfig.MODE_V1_MC ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.V1_DEFAULT_SAMPLES),
                    JSDivergenceConfig.V1_DEFAULT_SAMPLES, Pathway.MC);
            case JSDivergenceConfig.MODE_V2_STRATIFIED ->
                new EvaluationResult(
                    stratifiedSampler.computeJSD(gmm1, gmm2, JSDivergenceConfig.V2_STRATIFIED_SAMPLES),
                    JSDivergenceConfig.V2_STRATIFIED_SAMPLES, Pathway.STRATIFIED);
            case JSDivergenceConfig.MODE_V3_SPRT -> {
                double[] result = sprtSampler.computeJSDWithStats(gmm1, gmm2, JSDivergenceConfig.V3_SPRT_MAX_SAMPLES);
                yield new EvaluationResult(result[0], (int) result[1], Pathway.SPRT);
            }
            case JSDivergenceConfig.MODE_V4_BOUNDS -> {
                double[] result = boundsSampler.computeJSDWithFilter(gmm1, gmm2, JSDivergenceConfig.V4_BOUNDS_MAX_SAMPLES);
                boolean conclusive = result[BoundsFilterSampler.FILTER_CONCLUSIVE] > 0.5;
                if (conclusive || BOUNDS_ONLY) {
                    // Conclusive reject, or the legacy bound-only baseline was requested.
                    yield new EvaluationResult(result[BoundsFilterSampler.FILTER_BOUND],
                        (int) result[BoundsFilterSampler.FILTER_SAMPLES], Pathway.BOUNDS);
                }
                // The bound could not settle the decision: a lower bound below the
                // threshold carries no information about the true JSD, so refine.
                yield new EvaluationResult(
                    computeMC(gmm1, gmm2, JSDivergenceConfig.V4_BOUNDS_MAX_SAMPLES),
                    JSDivergenceConfig.V4_BOUNDS_MAX_SAMPLES, Pathway.BOUNDS_REFINED);
            }
            case JSDivergenceConfig.MODE_V5_ADAPTIVE -> {
                double[] result = adaptiveSampler.computeJSDAdaptive(gmm1, gmm2, JSDivergenceConfig.V5_ADAPTIVE_MAX_SAMPLES);
                yield new EvaluationResult(result[0], (int) result[1], adaptivePathway((int) result[2]));
            }
            default ->
                new EvaluationResult(computeMC(gmm1, gmm2, JSDivergenceConfig.V1_DEFAULT_SAMPLES),
                    JSDivergenceConfig.V1_DEFAULT_SAMPLES, Pathway.MC);
        };
    }

    private Pathway adaptivePathway(int method) {
        return switch (method) {
            case 0 -> Pathway.ADAPTIVE_BOUNDS;
            case 1 -> Pathway.ADAPTIVE_SPRT;
            case 2 -> Pathway.ADAPTIVE_STRATIFIED;
            default -> throw new IllegalArgumentException("Unknown adaptive pathway: " + method);
        };
    }

    private void validateCompatibility(GMMValue gmm1, GMMValue gmm2) {
        if (gmm1.getDimensions() != gmm2.getDimensions()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality. Got d1=" + gmm1.getDimensions() +
                    ", d2=" + gmm2.getDimensions());
        }
    }

    /**
     * Monte Carlo JSD estimate.
     *
     * <p>JSD is symmetric, so the estimate must be too. {@link #pairSeed} is already
     * order-independent, but the two operands consume the shared RNG stream in call
     * order, so swapping the arguments would otherwise yield a different realisation
     * of the estimate. The operands are therefore put in a canonical order before
     * sampling, making {@code computeMC(p,q)} and {@code computeMC(q,p)} bit-identical.</p>
     */
    private double computeMC(GMMValue p, GMMValue q, int numSamples) {
        GMMValue first = p;
        GMMValue second = q;
        if (!inCanonicalOrder(p, q)) {
            first = q;
            second = p;
        }
        Random rng = new Random(pairSeed(first, second));
        GMMValue m = createMixture(first, second);
        int half = numSamples / 2;
        double klFirstM = computeKLDivergence(first, m, half, rng);
        double klSecondM = computeKLDivergence(second, m, half, rng);
        return 0.5 * klFirstM + 0.5 * klSecondM;
    }

    /**
     * Total order on operands used to make symmetric estimators order-independent.
     * Falls back to the serialised form when hash codes collide so the ordering is
     * still deterministic.
     */
    private static boolean inCanonicalOrder(GMMValue p, GMMValue q) {
        int h1 = p.hashCode();
        int h2 = q.hashCode();
        if (h1 != h2) {
            return h1 <= h2;
        }
        return p.toJSON().compareTo(q.toJSON()) <= 0;
    }

    private GMMValue createMixture(GMMValue p, GMMValue q) {
        return GMMMixture.equalWeight(p, q);
    }

    private double computeKLDivergence(GMMValue p, GMMValue q, int numSamples, Random rng) {
        double sum = 0.0;

        for (int i = 0; i < numSamples; i++) {
            double[] sample = p.sampleOne(rng);
            double logP = p.logPdf(sample);
            double logQ = q.logPdf(sample);
            sum += (logP - logQ);
        }

        return sum / numSamples;
    }

    private long pairSeed(GMMValue p, GMMValue q) {
        int h1 = p.hashCode();
        int h2 = q.hashCode();
        long a = Integer.toUnsignedLong(Math.min(h1, h2));
        long b = Integer.toUnsignedLong(Math.max(h1, h2));
        return 0x9E3779B97F4A7C15L ^ (a * 0xBF58476D1CE4E5B9L) ^ (b * 0x94D049BB133111EBL);
    }
}
