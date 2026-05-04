package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase3;

/**
 * SPARQL function to create a weighted mixture of two GMMs.
 * 
 * <p>For GMM1 and GMM2 with mixing weight α ∈ [0,1], creates:</p>
 * <pre>
 * Mix = α * GMM1 + (1-α) * GMM2
 * </pre>
 * 
 * <p>The result is a GMM with K = K1 + K2 components:</p>
 * <ul>
 *   <li>Weights: [α*w1_1, ..., α*w1_K1, (1-α)*w2_1, ..., (1-α)*w2_K2]</li>
 *   <li>Means: [μ1_1, ..., μ1_K1, μ2_1, ..., μ2_K2]</li>
 *   <li>Covariances: [Σ1_1, ..., Σ1_K1, Σ2_1, ..., Σ2_K2]</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?mixture WHERE {
 *   ?var1 uq:hasDistribution ?gmm1 .
 *   ?var2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:mix(?gmm1, ?gmm2, 0.7) AS ?mixture)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Mix extends FunctionBase3 {
    
    public static final String URI = "http://probsparql.org/function#mix";
    
    /**
     * Create weighted mixture of two GMMs.
     * 
     * @param gmm1Node First GMM
     * @param gmm2Node Second GMM
     * @param alphaNode Mixing weight α ∈ [0,1]
     * @return Mixed GMM
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node, NodeValue alphaNode) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        double alpha = extractDouble(alphaNode, "weight");
        
        // Validate mixing weight
        if (alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException(
                "Mixing weight must be in [0,1], got: " + alpha);
        }
        
        // Validate compatibility
        if (gmm1.getDimensions() != gmm2.getDimensions()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality for mixing. Got d1=" + 
                gmm1.getDimensions() + ", d2=" + gmm2.getDimensions());
        }
        
        GMMValue mixedGMM = computeMixture(gmm1, gmm2, alpha);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            mixedGMM.toJSON(), GMMDatatype.INSTANCE
        );
        return NodeValue.makeNode(node);
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
     * Extract double value from NodeValue.
     */
    private double extractDouble(NodeValue node, String name) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "The " + name + " must be a number");
        }
        return node.getDouble();
    }
    
    /**
     * Compute mixture by concatenating components with adjusted weights.
     * 
     * Result: [α*GMM1 components, (1-α)*GMM2 components]
     */
    private GMMValue computeMixture(GMMValue gmm1, GMMValue gmm2, double alpha) {
        int K1 = gmm1.getNComponents();
        int K2 = gmm2.getNComponents();
        int d = gmm1.getDimensions();
        
        int K = K1 + K2;
        
        double[] weights = new double[K];
        double[][] means = new double[K][d];
        double[][][] covariances = new double[K][][];
        
        // Copy GMM1 components with weight α
        for (int i = 0; i < K1; i++) {
            weights[i] = alpha * gmm1.getWeights()[i];
            means[i] = gmm1.getMeans()[i].clone();
            covariances[i] = cloneCovariance(gmm1.getCovariances()[i]);
        }
        
        // Copy GMM2 components with weight (1-α)
        for (int i = 0; i < K2; i++) {
            int idx = K1 + i;
            weights[idx] = (1 - alpha) * gmm2.getWeights()[i];
            means[idx] = gmm2.getMeans()[i].clone();
            covariances[idx] = cloneCovariance(gmm2.getCovariances()[i]);
        }
        
        // Result uses full covariance type (most general)
        // Convert both GMMs to full if needed
        double[][][] fullCovariances = new double[K][][];
        for (int i = 0; i < K1; i++) {
            fullCovariances[i] = toFullCovariance(
                covariances[i], 
                gmm1.getCovarianceType(), 
                d
            );
        }
        for (int i = 0; i < K2; i++) {
            fullCovariances[K1 + i] = toFullCovariance(
                covariances[K1 + i], 
                gmm2.getCovarianceType(), 
                d
            );
        }
        
        return new GMMValue(K, d, "full", weights, means, fullCovariances);
    }
    
    /**
     * Clone covariance matrix.
     */
    private double[][] cloneCovariance(double[][] cov) {
        double[][] clone = new double[cov.length][];
        for (int i = 0; i < cov.length; i++) {
            clone[i] = cov[i].clone();
        }
        return clone;
    }
    
    /**
     * Convert covariance to full matrix form.
     */
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        switch (type) {
            case "full":
                // Already full - just copy
                for (int i = 0; i < d; i++) {
                    full[i] = cov[i].clone();
                }
                return full;
                
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
}
