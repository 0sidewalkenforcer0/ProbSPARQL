package org.apache.jena.probsparql.functions.comparison;

/**
 * Configuration for JSDivergence computation.
 * All parameters are configurable via Java System Properties.
 * 
 * System Properties:
 * - probsparql.mode: GT_100, GT_1K, GT_5K, GT_10K, V1_MC, V2_STRATIFIED, V3_SPRT, V4_BOUNDS, V5_ADAPTIVE
 * - probsparql.sprt.alpha: SPRT false positive rate (default: 0.05)
 * - probsparql.sprt.beta: SPRT false negative rate (default: 0.05)
 * - probsparql.sprt.epsilon: SPRT decision threshold (default: 0.05)
 * - probsparql.samples: Default sample count (default: 1000)
 * 
 * @author ProbSPARQL Team
 */
public class JSDivergenceConfig {
    
    // Sampling modes
    public static final String MODE_GT_100 = "GT_100";
    public static final String MODE_GT_1K = "GT_1K";
    public static final String MODE_GT_5K = "GT_5K";
    public static final String MODE_GT_10K = "GT_10K";
    public static final String MODE_V1_MC = "V1_MC";
    public static final String MODE_V2_STRATIFIED = "V2_STRATIFIED";
    public static final String MODE_V3_SPRT = "V3_SPRT";
    public static final String MODE_V4_BOUNDS = "V4_BOUNDS";
    public static final String MODE_V5_ADAPTIVE = "V5_ADAPTIVE";
    
    // System property keys
    private static final String PROP_MODE = "probsparql.mode";
    private static final String PROP_SPRT_ALPHA = "probsparql.sprt.alpha";
    private static final String PROP_SPRT_BETA = "probsparql.sprt.beta";
    private static final String PROP_SPRT_EPSILON = "probsparql.sprt.epsilon";
    private static final String PROP_SAMPLES = "probsparql.samples";
    
    // Default values
    private static final String DEFAULT_MODE = MODE_V5_ADAPTIVE;
    private static final double DEFAULT_ALPHA = 0.05;
    private static final double DEFAULT_BETA = 0.05;
    private static final double DEFAULT_EPSILON = 0.3;  // calibrated to classification threshold θ=0.3
    private static final int DEFAULT_SAMPLES = 1000;
    
    // Cached values — MODE is mutable so benchmarks can switch modes at runtime
    public static volatile String MODE;
    public static final double SPRT_ALPHA;
    public static final double SPRT_BETA;
    public static final double SPRT_EPSILON;
    public static final int DEFAULT_SAMPLE_COUNT;
    
    // Sample counts for GT modes
    public static final int GT_100_SAMPLES = 100;
    public static final int GT_1K_SAMPLES = 1000;
    public static final int GT_5K_SAMPLES = 5000;
    public static final int GT_10K_SAMPLES = 10000;
    
    // V1-V5 configuration — unified sample budget = 5000
    public static final int V1_DEFAULT_SAMPLES = 5000;
    public static final int V2_STRATIFIED_SAMPLES = 5000;
    public static final int V3_SPRT_MAX_SAMPLES = 5000;
    public static final int V4_BOUNDS_MAX_SAMPLES = 5000;
    public static final int V5_ADAPTIVE_MAX_SAMPLES = 5000;
    
    static {
        MODE = System.getProperty(PROP_MODE, DEFAULT_MODE);
        SPRT_ALPHA = Double.parseDouble(System.getProperty(PROP_SPRT_ALPHA, String.valueOf(DEFAULT_ALPHA)));
        SPRT_BETA = Double.parseDouble(System.getProperty(PROP_SPRT_BETA, String.valueOf(DEFAULT_BETA)));
        SPRT_EPSILON = Double.parseDouble(System.getProperty(PROP_SPRT_EPSILON, String.valueOf(DEFAULT_EPSILON)));
        DEFAULT_SAMPLE_COUNT = Integer.parseInt(System.getProperty(PROP_SAMPLES, String.valueOf(DEFAULT_SAMPLES)));
    }
    
    /**
     * Reload MODE from system property. Call this before switching modes at runtime.
     */
    public static void reloadMode() {
        MODE = System.getProperty(PROP_MODE, DEFAULT_MODE);
    }
    
    /**
     * Get sample count for current mode.
     */
    public static int getSampleCount() {
        switch (MODE) {
            case MODE_GT_100:
                return GT_100_SAMPLES;
            case MODE_GT_1K:
                return GT_1K_SAMPLES;
            case MODE_GT_5K:
                return GT_5K_SAMPLES;
            case MODE_GT_10K:
                return GT_10K_SAMPLES;
            case MODE_V1_MC:
                return V1_DEFAULT_SAMPLES;
            case MODE_V2_STRATIFIED:
                return V2_STRATIFIED_SAMPLES;
            case MODE_V3_SPRT:
                return V3_SPRT_MAX_SAMPLES;
            case MODE_V4_BOUNDS:
                return V4_BOUNDS_MAX_SAMPLES;
            case MODE_V5_ADAPTIVE:
            default:
                return V5_ADAPTIVE_MAX_SAMPLES;
        }
    }
    
    /**
     * Check if current mode uses SPRT.
     */
    public static boolean usesSPRT() {
        return MODE.equals(MODE_V3_SPRT) || MODE.equals(MODE_V5_ADAPTIVE);
    }
    
    /**
     * Check if current mode uses bounds filter.
     */
    public static boolean usesBounds() {
        return MODE.equals(MODE_V4_BOUNDS) || MODE.equals(MODE_V5_ADAPTIVE);
    }
    
    /**
     * Check if current mode uses stratified sampling.
     */
    public static boolean usesStratified() {
        return MODE.equals(MODE_V2_STRATIFIED) || MODE.equals(MODE_V3_SPRT) 
            || MODE.equals(MODE_V4_BOUNDS) || MODE.equals(MODE_V5_ADAPTIVE);
    }
    
    /**
     * Check if current mode is Ground Truth (high sample count).
     */
    public static boolean isGroundTruth() {
        return MODE.startsWith("GT_");
    }
    
    /**
     * Print current configuration (useful for debugging).
     */
    public static void printConfig() {
        System.out.println("=== JSDivergence Configuration ===");
        System.out.println("Mode: " + MODE);
        System.out.println("Sample Count: " + getSampleCount());
        if (usesSPRT()) {
            System.out.println("SPRT Alpha: " + SPRT_ALPHA);
            System.out.println("SPRT Beta: " + SPRT_BETA);
            System.out.println("SPRT Epsilon: " + SPRT_EPSILON);
        }
        System.out.println("=================================");
    }
}
