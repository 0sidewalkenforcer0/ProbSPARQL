package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to shift a GMM by a constant offset.
 * 
 * <p>For Y = X + b where X ~ GMM, the result is:</p>
 * <ul>
 *   <li>Means: μ_Y = μ_X + b</li>
 *   <li>Covariances: Σ_Y = Σ_X (unchanged)</li>
 *   <li>Weights: unchanged</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?shiftedDist WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:shift(?gmm, 3.0) AS ?shiftedDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Shift extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#shift";
    
    /**
     * Shift a GMM by a constant offset.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param offsetNode NodeValue containing shift offset (numeric)
     * @return Shifted GMM as a new GMM literal
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue offsetNode) {
        GMMValue gmm = extractGMM(gmmNode);
        double offset = extractOffset(offsetNode);
        
        GMMValue shiftedGMM = shiftGMM(gmm, offset);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            shiftedGMM.toJSON(), GMMDatatype.INSTANCE
        );
        return NodeValue.makeNode(node);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException("First argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "First argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Extract offset from NodeValue.
     */
    private double extractOffset(NodeValue node) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "Second argument must be a numeric offset");
        }
        
        return node.getDouble();
    }
    
    /**
     * Shift GMM by a constant offset.
     * 
     * Y = X + b => μ_Y = μ_X + b, Σ_Y = Σ_X
     */
    private GMMValue shiftGMM(GMMValue gmm, double b) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String covType = gmm.getCovarianceType();
        
        double[] weights = gmm.getWeights().clone();
        
        // Shift means: μ_Y = μ_X + b
        double[][] means = new double[K][d];
        for (int k = 0; k < K; k++) {
            for (int i = 0; i < d; i++) {
                means[k][i] = gmm.getMeans()[k][i] + b;
            }
        }
        
        // Covariances unchanged: Σ_Y = Σ_X
        double[][][] covariances = new double[K][][];
        for (int k = 0; k < K; k++) {
            covariances[k] = cloneCovariance(gmm.getCovariances()[k], covType, d);
        }
        
        return new GMMValue(K, d, covType, weights, means, covariances);
    }
    
    /**
     * Clone covariance matrix.
     */
    private double[][] cloneCovariance(double[][] cov, String covType, int d) {
        switch (covType) {
            case "full":
                double[][] fullCov = new double[d][d];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        fullCov[i][j] = cov[i][j];
                    }
                }
                return fullCov;
                
            case "diag":
                double[][] diagCov = new double[1][d];
                for (int i = 0; i < d; i++) {
                    diagCov[0][i] = cov[0][i];
                }
                return diagCov;
                
            case "spherical":
                return new double[][] {{cov[0][0]}};
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
}
