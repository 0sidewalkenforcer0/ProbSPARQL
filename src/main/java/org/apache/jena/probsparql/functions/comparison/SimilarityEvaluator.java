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

    public enum Pathway {
        MC,
        STRATIFIED,
        SPRT,
        BOUNDS,
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
        validateTailProbability("alpha", alpha);
        validateTailProbability("beta", beta);
        this.mode = System.getProperty("probsparql.mode", JSDivergenceConfig.MODE);
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
                yield new EvaluationResult(result[0], (int) result[1], Pathway.BOUNDS);
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

    private double computeMC(GMMValue p, GMMValue q, int numSamples) {
        Random rng = new Random(pairSeed(p, q));
        GMMValue m = createMixture(p, q);
        double klPM = computeKLDivergence(p, m, numSamples / 2, rng);
        double klQM = computeKLDivergence(q, m, numSamples / 2, rng);
        return 0.5 * klPM + 0.5 * klQM;
    }

    private GMMValue createMixture(GMMValue p, GMMValue q) {
        int kP = p.getNComponents();
        int kQ = q.getNComponents();
        int kM = kP + kQ;
        int d = p.getDimensions();

        double[] weightsP = p.getWeights();
        double[] weightsQ = q.getWeights();
        double[][] meansP = p.getMeans();
        double[][] meansQ = q.getMeans();
        double[][][] covariancesP = p.getCovariances();
        double[][][] covariancesQ = q.getCovariances();
        String covType = p.getCovarianceType();

        double[] weightsM = new double[kM];
        double[][] meansM = new double[kM][d];
        double[][][] covariancesM = new double[kM][][];

        for (int k = 0; k < kP; k++) {
            weightsM[k] = 0.5 * weightsP[k];
            meansM[k] = meansP[k].clone();
            covariancesM[k] = cloneCovariance(covariancesP[k], covType);
        }

        for (int k = 0; k < kQ; k++) {
            weightsM[kP + k] = 0.5 * weightsQ[k];
            meansM[kP + k] = meansQ[k].clone();
            covariancesM[kP + k] = cloneCovariance(covariancesQ[k], covType);
        }

        return new GMMValue(kM, d, covType, weightsM, meansM, covariancesM);
    }

    private double[][] cloneCovariance(double[][] cov, String covType) {
        return switch (covType) {
            case "full" -> {
                int d = cov.length;
                double[][] fullCopy = new double[d][d];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        fullCopy[i][j] = cov[i][j];
                    }
                }
                yield fullCopy;
            }
            case "diag" -> {
                double[][] diagCopy = new double[1][cov[0].length];
                System.arraycopy(cov[0], 0, diagCopy[0], 0, cov[0].length);
                yield diagCopy;
            }
            case "spherical" -> new double[][] {{cov[0][0]}};
            default -> throw new IllegalStateException("Unknown covariance type: " + covType);
        };
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
