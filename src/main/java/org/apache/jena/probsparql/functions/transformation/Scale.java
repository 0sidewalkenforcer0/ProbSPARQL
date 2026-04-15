package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to scale a GMM by a constant factor.
 * 
 * <p>For Y = c * X where X ~ GMM, the result is:</p>
 * <ul>
 *   <li>Means: μ_Y = c * μ_X</li>
 *   <li>Covariances: Σ_Y = c² * Σ_X</li>
 *   <li>Weights: unchanged</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?scaledDist WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:scale(?gmm, 2.0) AS ?scaledDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Scale extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#scale";
    
    /**
     * Scale a GMM by a constant factor.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param scaleNode NodeValue containing scale factor (numeric)
     * @return Scaled GMM as a new GMM literal
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue scaleNode) {
        GMMValue gmm = extractGMM(gmmNode);
        double scaleFactor = extractScaleFactor(scaleNode);
        
        GMMValue scaledGMM = scaleGMM(gmm, scaleFactor);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            scaledGMM.toJSON(), GMMDatatype.INSTANCE
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
     * Extract scale factor from NodeValue.
     */
    private double extractScaleFactor(NodeValue node) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "Second argument must be a numeric scale factor");
        }
        
        return node.getDouble();
    }
    
    /**
     * Scale GMM by a constant factor.
     * 
     * Y = c * X => μ_Y = c * μ_X, Σ_Y = c² * Σ_X
     */
    private GMMValue scaleGMM(GMMValue gmm, double c) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String covType = gmm.getCovarianceType();
        
        double[] weights = gmm.getWeights().clone();
        
        // Scale means: μ_Y = c * μ_X
        double[][] means = new double[K][d];
        for (int k = 0; k < K; k++) {
            for (int i = 0; i < d; i++) {
                means[k][i] = c * gmm.getMeans()[k][i];
            }
        }
        
        // Scale covariances: Σ_Y = c² * Σ_X
        double[][][] covariances = new double[K][][];
        double c2 = c * c;
        
        for (int k = 0; k < K; k++) {
            covariances[k] = scaleCovariance(gmm.getCovariances()[k], c2, covType, d);
        }
        
        return new GMMValue(K, d, covType, weights, means, covariances);
    }
    
    /**
     * Scale covariance matrix by c².
     */
    private double[][] scaleCovariance(double[][] cov, double c2, String covType, int d) {
        switch (covType) {
            case "full":
                double[][] fullCov = new double[d][d];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        fullCov[i][j] = c2 * cov[i][j];
                    }
                }
                return fullCov;
                
            case "diag":
                double[][] diagCov = new double[d][1];
                for (int i = 0; i < d; i++) {
                    diagCov[i][0] = c2 * cov[i][0];
                }
                return diagCov;
                
            case "spherical":
                return new double[][] {{c2 * cov[0][0]}};
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
}
