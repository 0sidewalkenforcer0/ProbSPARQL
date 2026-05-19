package org.apache.jena.probsparql;

import org.apache.jena.datatypes.TypeMapper;
import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.probsparql.functions.comparison.PolyJSD;
import org.apache.jena.probsparql.functions.comparison.SameDistribution;
import org.apache.jena.probsparql.functions.comparison.SameTerm;
import org.apache.jena.probsparql.functions.comparison.SimilarityEvaluator;
import org.apache.jena.probsparql.functions.thresholding.HistogramCDF;
import org.apache.jena.probsparql.functions.manipulation.HistogramMean;
import org.apache.jena.probsparql.functions.thresholding.PDF;
import org.apache.jena.probsparql.functions.thresholding.CDF;
import org.apache.jena.probsparql.functions.thresholding.LogPDF;
import org.apache.jena.probsparql.functions.thresholding.LogCDF;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.comparison.JSDivergence;
import org.apache.jena.probsparql.functions.comparison.JSDMode;
import org.apache.jena.probsparql.functions.comparison.LastDivJoinStats;
import org.apache.jena.probsparql.functions.transformation.Scale;
import org.apache.jena.probsparql.functions.transformation.Shift;
import org.apache.jena.probsparql.functions.transformation.LinearTransform;
import org.apache.jena.probsparql.functions.transformation.Marginal;
import org.apache.jena.probsparql.functions.transformation.Joint;
import org.apache.jena.probsparql.functions.transformation.Convolve;
import org.apache.jena.probsparql.functions.transformation.Multiply;
import org.apache.jena.probsparql.functions.manipulation.Mean;
import org.apache.jena.probsparql.functions.manipulation.Std;
import org.apache.jena.probsparql.functions.manipulation.Map;
import org.apache.jena.probsparql.functions.manipulation.ModeCount;
import org.apache.jena.probsparql.functions.manipulation.Mix;
import org.apache.jena.probsparql.functions.manipulation.Fuse;
import org.apache.jena.probsparql.functions.manipulation.Quantile;
import org.apache.jena.probsparql.functions.manipulation.Sample;
import org.apache.jena.probsparql.propertyfunctions.ExactJoinPF;
import org.apache.jena.probsparql.propertyfunctions.FuzzyJoinPF;
import org.apache.jena.sparql.algebra.Algebra;
import org.apache.jena.sparql.algebra.AlgebraGeneratorProbabilistic;
import org.apache.jena.sparql.engine.QueryEngineProbabilistic;
import org.apache.jena.sparql.engine.QueryEngineRegistry;
import org.apache.jena.sparql.engine.join.ProbabilisticJoins;
import org.apache.jena.sparql.engine.main.OpExecutorProbabilistic;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.function.FunctionRegistry;
import org.apache.jena.sparql.pfunction.PropertyFunctionRegistry;
import org.apache.jena.sparql.util.Symbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for ProbSPARQL - Probabilistic SPARQL with 
 * attribute-level uncertainty support.
 * 
 * This class provides initialization and configuration for the
 * probabilistic SPARQL extension to Apache Jena.
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0-SNAPSHOT
 */
public class ProbSPARQL {

    private enum DistributionKind {
        GMM,
        HISTOGRAM,
        DIRICHLET
    }
    
    private static final Logger logger = LoggerFactory.getLogger(ProbSPARQL.class);
    
    public static final String VERSION = "1.0.0-SNAPSHOT";
    public static final String NAME = "ProbSPARQL";
    
    public static final Symbol FUSEJOIN_LEFT_VAR = Symbol.create("http://probsparql.org/fusejoin#leftVar");
    public static final Symbol FUSEJOIN_RIGHT_VAR = Symbol.create("http://probsparql.org/fusejoin#rightVar");
    public static final Symbol FUSEJOIN_TOLERANCE = Symbol.create("http://probsparql.org/fusejoin#tolerance");
    public static final Symbol FUSEJOIN_RESULT_VAR = Symbol.create("http://probsparql.org/fusejoin#resultVar");
    // New: Pattern information for relational semantics
    public static final Symbol FUSEJOIN_LEFT_PATTERN = Symbol.create("http://probsparql.org/fusejoin#leftPattern");
    public static final Symbol FUSEJOIN_RIGHT_PATTERN = Symbol.create("http://probsparql.org/fusejoin#rightPattern");
    
    // Context symbols for the internal SimilarityJoin metadata used by DIVJOIN.
    public static final Symbol SIMILARITYJOIN_LEFT_VAR = Symbol.create("http://probsparql.org/similarityjoin#leftVar");
    public static final Symbol SIMILARITYJOIN_RIGHT_VAR = Symbol.create("http://probsparql.org/similarityjoin#rightVar");
    public static final Symbol SIMILARITYJOIN_TOLERANCE = Symbol.create("http://probsparql.org/similarityjoin#tolerance");
    // New: Pattern information for relational semantics
    public static final Symbol SIMILARITYJOIN_LEFT_PATTERN = Symbol.create("http://probsparql.org/similarityjoin#leftPattern");
    public static final Symbol SIMILARITYJOIN_RIGHT_PATTERN = Symbol.create("http://probsparql.org/similarityjoin#rightPattern");
    
    private static boolean initialized = false;
    
    /**
     * Initialize the ProbSPARQL system.
     * This method should be called before using any ProbSPARQL functionality.
     * It is safe to call this method multiple times - subsequent calls will be ignored.
     */
    public static synchronized void init() {
        if (initialized) {
            logger.debug("ProbSPARQL already initialized");
            return;
        }
        
        logger.info("Initializing {} {}", NAME, VERSION);
        
        // Ensure Jena system is initialized
        org.apache.jena.sys.JenaSystem.init();
        
        // Register custom datatypes
        TypeMapper.getInstance().registerDatatype(GMMDatatype.INSTANCE);
        logger.info("Registered custom datatype: {}", GMMDatatype.URI);
        TypeMapper.getInstance().registerDatatype(HistogramDatatype.INSTANCE);
        logger.info("Registered custom datatype: {}", HistogramDatatype.URI);
        TypeMapper.getInstance().registerDatatype(DirichletDatatype.INSTANCE);
        logger.info("Registered custom datatype: {}", DirichletDatatype.URI);
        
        // Register custom SPARQL functions for probabilistic operators
        FunctionRegistry functionRegistry = FunctionRegistry.get();
        
        // Category 1: Probabilistic Thresholding Operators
        functionRegistry.put(PDF.URI, PDF.class);
        functionRegistry.put(CDF.URI, CDF.class);
        functionRegistry.put(LogPDF.URI, LogPDF.class);
        functionRegistry.put(LogCDF.URI, LogCDF.class);
        functionRegistry.put(HistogramCDF.URI, HistogramCDF.class);
        logger.info("Registered {} thresholding functions", 5);
        
        // Category 2: Probabilistic Comparison Operators
        functionRegistry.put(KLDivergence.URI, KLDivergence.class);
        functionRegistry.put(JSDivergence.URI, JSDivergence.class);
        functionRegistry.put(HistogramJSD.URI, HistogramJSD.class);
        functionRegistry.put(PolyJSD.URI, PolyJSD.class);   // polymorphic prob:jsd
        functionRegistry.put(JSDMode.URI, JSDMode.class);   // benchmark mode-specific GMM JSD
        functionRegistry.put(LastDivJoinStats.URI, LastDivJoinStats.class);
        functionRegistry.put(SameTerm.URI, SameTerm.class);
        functionRegistry.put(SameDistribution.URI, SameDistribution.class);
        logger.info("Registered {} comparison functions", 8);
        
        // Category 3: Probabilistic Transformation Operators
        functionRegistry.put(Scale.URI, Scale.class);
        functionRegistry.put(Shift.URI, Shift.class);
        functionRegistry.put(LinearTransform.URI, LinearTransform.class);
        functionRegistry.put(Marginal.URI, Marginal.class);
        functionRegistry.put(Joint.URI, Joint.class);
        functionRegistry.put(Convolve.URI, Convolve.class);
        functionRegistry.put(Multiply.URI, Multiply.class);
        logger.info("Registered {} transformation functions", 7);
        
        // Category 4: Distribution Manipulation Operators
        functionRegistry.put(Mean.URI, Mean.class);
        functionRegistry.put(Std.URI, Std.class);
        functionRegistry.put(Map.URI, Map.class);
        functionRegistry.put(ModeCount.URI, ModeCount.class);
        functionRegistry.put(Mix.URI, Mix.class);
        functionRegistry.put(Fuse.URI, Fuse.class);
        functionRegistry.put(Quantile.URI, Quantile.class);
        functionRegistry.put(HistogramMean.URI, HistogramMean.class);
        functionRegistry.put(Sample.URI, Sample.class);
        logger.info("Registered {} manipulation functions", 9);
        
        // Category 5: Probabilistic Property Functions (JOIN operations)
        PropertyFunctionRegistry pfRegistry = PropertyFunctionRegistry.get();
        pfRegistry.put(ExactJoinPF.URI, ExactJoinPF.class);
        pfRegistry.put(FuzzyJoinPF.URI, FuzzyJoinPF.class);
        logger.info("Registered {} property functions for probabilistic joins", 2);
        
        // Category 6: Engine-level Probabilistic Join Framework
        // Initialize the registry-based join framework (similar to Distances.java)
        // This provides algebra-level join operators with extensible strategies
        logger.info("Initialized probabilistic join framework:");
        logger.info("  - Available active strategies: [{}, {}]",
                ProbabilisticJoins.EXACT_JOIN, ProbabilisticJoins.FUZZY_JOIN);
        for (String strategyURI : ProbabilisticJoins.getRegisteredStrategies()) {
            if (ProbabilisticJoins.FUSE_JOIN.equals(strategyURI)) {
                continue;
            }
            ProbabilisticJoins.ProbJoinFunc func = ProbabilisticJoins.getJoinStrategy(strategyURI);
            logger.info("    {} : {}", strategyURI, func.getDescription());
        }
        
        // Category 7: Register QueryEngineProbabilistic for DIVJOIN support.
        QueryEngineRegistry.addFactory(new QueryEngineProbabilistic.Factory());
        logger.info("Registered QueryEngineProbabilistic for DIVJOIN syntax support");
        
        initialized = true;
        logger.info("{} initialization complete (29 functions + 2 property functions + DIVJOIN syntax)", NAME);
    }
    
    /**
     * Check if ProbSPARQL has been initialized.
     * 
     * @return true if initialized, false otherwise
     */
    public static boolean isInitialized() {
        return initialized;
    }
    
    /**
     * Get the ProbSPARQL version string.
     * 
     * @return version string
     */
    public static String getVersion() {
        return VERSION;
    }
    
    /**
     * Internal similarity-evaluation entry point used by join operators that
     * already know the query tolerance.
     *
     * <p>For V3/V4/V5 this tolerance is threaded into the sequential decision
     * logic instead of relying on the global default threshold.</p>
     */
    public static double evaluateSimilarity(org.apache.jena.graph.Node leftNode,
                                            org.apache.jena.graph.Node rightNode,
                                            double tolerance) {
        return evaluateSimilarityInternal(leftNode, rightNode, tolerance, null);
    }

    /**
     * Internal similarity-evaluation entry point for query operators that
     * supply both a JSD threshold and a one-sided tail probability for the
     * sequential confidence bounds used by V3/V5.
     */
    public static double evaluateSimilarity(org.apache.jena.graph.Node leftNode,
                                            org.apache.jena.graph.Node rightNode,
                                            double tolerance,
                                            double tailProbability) {
        return evaluateSimilarityInternal(leftNode, rightNode, tolerance, tailProbability);
    }

    /**
     * Legacy helper that preserves the historical {@code JSDivergence()} naming
     * and configuration semantics for callers that truly want the old behavior.
     */
    public static double legacyJSDivergence(org.apache.jena.graph.Node leftNode,
                                            org.apache.jena.graph.Node rightNode) {
        return SimilarityEvaluator.legacy().evaluate(extractGMM(leftNode), extractGMM(rightNode));
    }

    public static boolean supportsSimilarityLiteral(org.apache.jena.graph.Node node) {
        if (node == null || !node.isLiteral()) {
            return false;
        }
        Object value = node.getLiteralValue();
        return value instanceof GMMValue
            || value instanceof HistogramValue
            || value instanceof DirichletValue;
    }

    /**
     * @deprecated Use {@link #evaluateSimilarity(org.apache.jena.graph.Node, org.apache.jena.graph.Node, double)}
     *             for threshold-aware similarity evaluation or
     *             {@link #legacyJSDivergence(org.apache.jena.graph.Node, org.apache.jena.graph.Node)}
     *             when explicitly preserving the old scalar-function behavior.
     */
    @Deprecated
    public static double JSDivergence(org.apache.jena.graph.Node leftNode, org.apache.jena.graph.Node rightNode) {
        return legacyJSDivergence(leftNode, rightNode);
    }

    private static double evaluateSimilarityInternal(org.apache.jena.graph.Node leftNode,
                                                     org.apache.jena.graph.Node rightNode,
                                                     double tolerance,
                                                     Double tailProbability) {
        validateSupportedDistribution(leftNode, "left");
        validateSupportedDistribution(rightNode, "right");
        validateDimensionalCompatibility(leftNode, rightNode);

        DistributionKind leftKind = distributionKind(leftNode.getLiteralValue());
        DistributionKind rightKind = distributionKind(rightNode.getLiteralValue());

        if (leftKind == DistributionKind.GMM && rightKind == DistributionKind.GMM) {
            GMMValue leftGmm = extractGMM(leftNode);
            GMMValue rightGmm = extractGMM(rightNode);
            if (tailProbability == null) {
                return new SimilarityEvaluator(tolerance).evaluate(leftGmm, rightGmm);
            }
            return new SimilarityEvaluator(tolerance, tailProbability, tailProbability)
                .evaluate(leftGmm, rightGmm);
        }

        return new PolyJSD().exec(
            org.apache.jena.sparql.expr.NodeValue.makeNode(leftNode),
            org.apache.jena.sparql.expr.NodeValue.makeNode(rightNode)
        ).getDouble();
    }

    private static GMMValue extractGMM(org.apache.jena.graph.Node node) {
        if (node == null || !node.isLiteral()
            || !(node.getLiteralValue() instanceof GMMValue gmm)) {
            throw new IllegalArgumentException("Similarity evaluation requires a GMM literal");
        }
        return gmm;
    }

    private static void validateSupportedDistribution(org.apache.jena.graph.Node node, String side) {
        if (!supportsSimilarityLiteral(node)) {
            String datatype = node == null || !node.isLiteral() ? "<non-literal>" : node.getLiteralDatatypeURI();
            throw new IllegalArgumentException(
                "Similarity evaluation requires a supported probabilistic literal on the "
                    + side + " side, got: " + datatype);
        }
    }

    private static void validateDimensionalCompatibility(org.apache.jena.graph.Node leftNode,
                                                         org.apache.jena.graph.Node rightNode) {
        int leftDim = distributionDimensions(leftNode.getLiteralValue());
        int rightDim = distributionDimensions(rightNode.getLiteralValue());
        if (leftDim != rightDim) {
            throw new IllegalArgumentException(
                "Similarity evaluation requires matching dimensionality. Got left="
                    + leftDim + ", right=" + rightDim);
        }
    }

    private static int distributionDimensions(Object value) {
        return switch (distributionKind(value)) {
            case GMM -> ((GMMValue) value).getDimensions();
            case HISTOGRAM -> ((HistogramValue) value).getDimensions();
            case DIRICHLET -> ((DirichletValue) value).getDimensions();
        };
    }

    private static DistributionKind distributionKind(Object value) {
        if (value instanceof GMMValue) {
            return DistributionKind.GMM;
        }
        if (value instanceof HistogramValue) {
            return DistributionKind.HISTOGRAM;
        }
        if (value instanceof DirichletValue) {
            return DistributionKind.DIRICHLET;
        }
        throw new IllegalArgumentException(
            "Unsupported probabilistic literal value type: "
                + (value == null ? "null" : value.getClass().getName()));
    }
    
    /**
     * Helper method: Fuse two GMM distributions using Bayesian fusion.
     * Used by QueryIterFuseJoin.
     * 
     * @param leftNode  First GMM distribution node
     * @param rightNode Second GMM distribution node
     * @return Fused GMM distribution node
     */
    public static org.apache.jena.graph.Node Fuse(org.apache.jena.graph.Node leftNode, org.apache.jena.graph.Node rightNode) {
        org.apache.jena.probsparql.functions.manipulation.Fuse fuseFunc = 
            new org.apache.jena.probsparql.functions.manipulation.Fuse();
        org.apache.jena.sparql.expr.NodeValue result = fuseFunc.exec(
            org.apache.jena.sparql.expr.NodeValue.makeNode(leftNode),
            org.apache.jena.sparql.expr.NodeValue.makeNode(rightNode)
        );
        return result.asNode();
    }
    
    /**
     * Main method for command-line execution.
     * 
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        System.out.println(NAME + " " + VERSION);
        System.out.println("Probabilistic SPARQL with Attribute-level Uncertainty Support");
        System.out.println("Based on Apache Jena ARQ");
        System.out.println();
        
        init();
        
        if (args.length == 0) {
            printUsage();
        } else {
            System.out.println("Command-line interface not yet implemented");
        }
    }
    
    private static void printUsage() {
        System.out.println("Usage: java -jar jena-probsparql.jar [options]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --query <file>     Execute SPARQL query from file");
        System.out.println("  --data <file>      Load RDF data with probabilities");
        System.out.println("  --help             Show this help message");
        System.out.println("  --version          Show version information");
    }
}
