package org.apache.jena.sparql.engine.join;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.functions.manipulation.Fuse;
import org.apache.jena.sparql.expr.NodeValue;

/**
 * Registry of probabilistic join strategies for GMM distributions.
 * 
 * <p>This class provides a framework for probabilistic joins similar to how
 * {@code Distances.java} provides distance metrics for similarity joins.
 * It supports multiple join strategies with a unified
 * interface for compatibility checking and distribution fusion.</p>
 * 
 * <p><b>Architecture Inspiration:</b> This design mirrors the registry pattern
 * used in similarity join frameworks, enabling extensible join strategies
 * through a common interface.</p>
 * 
 * <h3>Supported Join Strategies:</h3>
 * <ul>
 *   <li><b>exactJoin</b>: JS divergence ≈ 0 (identical distributions)</li>
 *   <li><b>fuzzyJoin</b>: JS divergence &lt; tolerance (similar distributions)</li>
 * </ul>
 * 
 * <h3>Usage Example:</h3>
 * <pre>
 * // Get fuzzy join strategy
 * ProbJoinFunc fuzzy = ProbabilisticJoins.getJoinStrategy(ProbabilisticJoins.FUZZY_JOIN);
 * 
 * // Check compatibility
 * if (fuzzy.isCompatible(gmm1, gmm2, 0.05)) {
 *     // Perform fusion
 *     GMMValue result = fuzzy.join(Arrays.asList(gmm1, gmm2), 0.05);
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0-SNAPSHOT
 * @see org.apache.jena.sparql.engine.join.Distances
 */
public class ProbabilisticJoins {
    
    /**
     * Namespace for probabilistic join URIs.
     */
    public static final String NS = "http://probsparql.org/join#";
    
    /**
     * URI for exact join strategy (JS divergence ≈ 0).
     */
    public static final String EXACT_JOIN = NS + "exact";
    
    /**
     * URI for fuzzy join strategy (JS divergence < tolerance).
     */
    public static final String FUZZY_JOIN = NS + "fuzzy";
    
    /**
     * URI for N-way fuse join strategy (all pairs compatible).
     */
    public static final String FUSE_JOIN = NS + "fuse";
    
    /**
     * Numerical tolerance for exact match (JS divergence).
     */
    private static final double EXACT_EPSILON = 1e-10;
    
    /**
     * Registry of probabilistic join strategies.
     */
    private static final Map<String, ProbJoinFunc> registry = new HashMap<>();
    
    static {
        // ==================== Exact Join Strategy ====================
        registry.put(EXACT_JOIN, new ProbJoinFunc() {
            @Override
            public boolean isCompatible(GMMValue gmm1, GMMValue gmm2, double tolerance) {
                // For exact join, ignore tolerance parameter
                double js = computeLegacyJSDivergence(gmm1, gmm2);
                return js < EXACT_EPSILON;
            }
            
            @Override
            public GMMValue join(List<GMMValue> gmms, double tolerance) {
                if (gmms.size() < 2) {
                    throw new IllegalArgumentException(
                        "Exact join requires at least 2 distributions, got " + gmms.size());
                }
                
                // For exact join, all distributions must be identical
                GMMValue first = gmms.get(0);
                for (int i = 1; i < gmms.size(); i++) {
                    if (!isCompatible(first, gmms.get(i), tolerance)) {
                        return null;  // No match
                    }
                }
                
                // All identical - fuse them iteratively
                GMMValue result = gmms.get(0);
                for (int i = 1; i < gmms.size(); i++) {
                    result = fuseTwoGMMs(result, gmms.get(i));
                }
                return result;
            }
            
            @Override
            public String getDescription() {
                return "Exact Join: Matches distributions with JS divergence ≈ 0 (identical distributions)";
            }
        });
        
        // ==================== Fuzzy Join Strategy ====================
        registry.put(FUZZY_JOIN, new ProbJoinFunc() {
            @Override
            public boolean isCompatible(GMMValue gmm1, GMMValue gmm2, double tolerance) {
                if (tolerance < 0 || tolerance > 1) {
                    throw new IllegalArgumentException(
                        "Tolerance must be in [0, 1], got: " + tolerance);
                }
                double js = computeSimilarityScore(gmm1, gmm2, tolerance);
                return js < tolerance;
            }
            
            @Override
            public GMMValue join(List<GMMValue> gmms, double tolerance) {
                if (gmms.size() < 2) {
                    throw new IllegalArgumentException(
                        "Fuzzy join requires at least 2 distributions, got " + gmms.size());
                }
                
                // For fuzzy join, all distributions must be similar (pairwise JS < tolerance)
                for (int i = 0; i < gmms.size(); i++) {
                    for (int j = i + 1; j < gmms.size(); j++) {
                        if (!isCompatible(gmms.get(i), gmms.get(j), tolerance)) {
                            return null;  // At least one pair incompatible
                        }
                    }
                }
                
                // All pairs compatible - fuse iteratively
                GMMValue result = gmms.get(0);
                for (int i = 1; i < gmms.size(); i++) {
                    result = fuseTwoGMMs(result, gmms.get(i));
                }
                return result;
            }
            
            @Override
            public String getDescription() {
                return "Fuzzy Join: Matches distributions with JS divergence < tolerance (similar distributions)";
            }
        });
        
        // ==================== N-way Fuse Join Strategy ====================
        registry.put(FUSE_JOIN, new ProbJoinFunc() {
            @Override
            public boolean isCompatible(GMMValue gmm1, GMMValue gmm2, double tolerance) {
                // Same as fuzzy join for pairwise compatibility
                if (tolerance < 0 || tolerance > 1) {
                    throw new IllegalArgumentException(
                        "Tolerance must be in [0, 1], got: " + tolerance);
                }
                double js = computeSimilarityScore(gmm1, gmm2, tolerance);
                return js < tolerance;
            }
            
            @Override
            public GMMValue join(List<GMMValue> gmms, double tolerance) {
                if (gmms.size() < 2) {
                    throw new IllegalArgumentException(
                        "Fuse join requires at least 2 distributions, got " + gmms.size());
                }
                
                // Type-aware validation: all GMMs must have same dimensionality
                int d = gmms.get(0).getDimensions();
                for (int i = 1; i < gmms.size(); i++) {
                    if (gmms.get(i).getDimensions() != d) {
                        throw new IllegalArgumentException(
                            "All distributions must have same dimensionality for fusion. " +
                            "Expected d=" + d + ", got d=" + gmms.get(i).getDimensions() +
                            " at position " + i);
                    }
                }
                
                // Pairwise compatibility check: all pairs must be compatible
                for (int i = 0; i < gmms.size(); i++) {
                    for (int j = i + 1; j < gmms.size(); j++) {
                        if (!isCompatible(gmms.get(i), gmms.get(j), tolerance)) {
                            return null;  // Incompatible pair found
                        }
                    }
                }
                
                // All pairs compatible - perform iterative Bayesian fusion
                // This is the key innovation: N-way fusion instead of just pairwise
                GMMValue result = gmms.get(0);
                for (int i = 1; i < gmms.size(); i++) {
                    result = fuseTwoGMMs(result, gmms.get(i));
                }
                return result;
            }
            
            @Override
            public String getDescription() {
                return "N-way Fuse Join: Multi-way fusion with pairwise compatibility checking " +
                       "(all pairs must have JS divergence < tolerance)";
            }
        });
    }
    
    /**
     * Probabilistic join function interface.
     * 
     * <p>Similar to {@code DistFunc} in the Distances framework, this interface
     * defines the contract for probabilistic join strategies.</p>
     */
    public interface ProbJoinFunc {
        /**
         * Check if two GMM distributions are compatible for joining.
         * 
         * @param gmm1 First GMM distribution
         * @param gmm2 Second GMM distribution
         * @param tolerance Compatibility tolerance (0 = exact, 1 = very loose)
         * @return true if distributions are compatible, false otherwise
         */
        boolean isCompatible(GMMValue gmm1, GMMValue gmm2, double tolerance);
        
        /**
         * Perform probabilistic join on multiple GMM distributions.
         * 
         * @param gmms List of GMM distributions to join (must have at least 2)
         * @param tolerance Compatibility tolerance
         * @return Fused GMM distribution, or null if join fails (incompatible)
         */
        GMMValue join(List<GMMValue> gmms, double tolerance);
        
        /**
         * Get human-readable description of this join strategy.
         * 
         * @return Description string
         */
        String getDescription();
    }
    
    /**
     * Get a registered probabilistic join strategy by URI.
     * 
     * @param joinURI URI of the join strategy (e.g., {@link #EXACT_JOIN})
     * @return Join strategy function, or null if not registered
     */
    public static ProbJoinFunc getJoinStrategy(String joinURI) {
        return registry.get(joinURI.toLowerCase());
    }
    
    /**
     * Register a custom probabilistic join strategy.
     * 
     * <p>This allows users to extend the framework with their own join strategies.</p>
     * 
     * @param joinURI URI for the custom strategy
     * @param joinFunc Join function implementation
     */
    public static void registerJoinStrategy(String joinURI, ProbJoinFunc joinFunc) {
        registry.put(joinURI.toLowerCase(), joinFunc);
    }
    
    /**
     * Get all registered join strategy URIs.
     * 
     * @return List of registered join URIs
     */
    public static List<String> getRegisteredStrategies() {
        return new ArrayList<>(registry.keySet());
    }
    
    /**
     * Compute Jensen-Shannon divergence between two GMMs.
     * 
     * <p>This is a helper method that delegates to the {@link JSDivergence} function.</p>
     * 
     * @param gmm1 First GMM
     * @param gmm2 Second GMM
     * @return JS divergence value in [0, 1]
     */
    private static double computeLegacyJSDivergence(GMMValue gmm1, GMMValue gmm2) {
        // Create NodeValues for JSDivergence function
        Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);

        return ProbSPARQL.legacyJSDivergence(node1, node2);
    }

    private static double computeSimilarityScore(GMMValue gmm1, GMMValue gmm2, double tolerance) {
        Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);

        return ProbSPARQL.evaluateSimilarity(node1, node2, tolerance);
    }
    
    /**
     * Fuse two GMMs using Bayesian fusion.
     * 
     * <p>This is a helper method that delegates to the {@link Fuse} function.</p>
     * 
     * @param gmm1 Prior GMM
     * @param gmm2 Likelihood GMM
     * @return Posterior (fused) GMM
     */
    private static GMMValue fuseTwoGMMs(GMMValue gmm1, GMMValue gmm2) {
        // Create NodeValues for Fuse function
        Node node1 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm1.toJSON(), GMMDatatype.INSTANCE);
        Node node2 = org.apache.jena.graph.NodeFactory.createLiteralDT(
            gmm2.toJSON(), GMMDatatype.INSTANCE);
        
        NodeValue nv1 = NodeValue.makeNode(node1);
        NodeValue nv2 = NodeValue.makeNode(node2);
        
        // Use Fuse function
        Fuse fuseFunc = new Fuse();
        NodeValue result = fuseFunc.exec(nv1, nv2);
        
        return (GMMValue) result.asNode().getLiteralValue();
    }
}
