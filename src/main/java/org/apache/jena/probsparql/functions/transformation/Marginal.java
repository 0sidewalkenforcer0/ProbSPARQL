package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the marginal distribution of a GMM.
 * 
 * <p>Extracts a subset of dimensions from a multivariate GMM.
 * Currently supports extracting a single dimension for 1D marginals.</p>
 * 
 * <p>For a GMM with d dimensions, marginalizing over dimension i gives:</p>
 * <ul>
 *   <li>K components (unchanged)</li>
 *   <li>Weights: unchanged</li>
 *   <li>Means: μ_i for each component</li>
 *   <li>Covariances: Σ_ii for each component</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?marginalDist WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:marginal(?gmm, 0) AS ?marginalDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Marginal extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#marginal";
    
    /**
     * Compute marginal distribution over specified dimension.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param dimNode NodeValue containing dimension index (0-based)
     * @return Marginal GMM (1-dimensional)
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue dimNode) {
        GMMValue gmm = extractGMM(gmmNode);
        int dimension = extractDimension(dimNode, gmm.getDimensions());
        
        GMMValue marginalGMM = computeMarginal(gmm, dimension);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            marginalGMM.toJSON(), GMMDatatype.INSTANCE
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
     * Extract dimension index from NodeValue.
     */
    private int extractDimension(NodeValue node, int maxDim) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "Second argument must be a numeric dimension index");
        }
        
        int dim = node.getInteger().intValue();
        
        if (dim < 0 || dim >= maxDim) {
            throw new IllegalArgumentException(
                "Dimension index " + dim + " out of range [0, " + (maxDim - 1) + "]");
        }
        
        return dim;
    }
    
    /**
     * Compute marginal distribution over specified dimension.
     */
    private GMMValue computeMarginal(GMMValue gmm, int dim) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String covType = gmm.getCovarianceType();
        
        // For 1D GMMs, marginal is the GMM itself
        if (d == 1) {
            if (dim != 0) {
                throw new IllegalArgumentException("For 1D GMM, dimension must be 0");
            }
            return gmm;
        }
        
        double[] weights = gmm.getWeights().clone();
        
        // Extract means for specified dimension
        double[][] means = new double[K][1];
        for (int k = 0; k < K; k++) {
            means[k][0] = gmm.getMeans()[k][dim];
        }
        
        // Extract covariances for specified dimension
        double[][][] covariances = new double[K][][];
        for (int k = 0; k < K; k++) {
            covariances[k] = extractCovarianceElement(
                gmm.getCovariances()[k], dim, covType, d
            );
        }
        
        // Marginal is always 1D with full covariance (1x1 matrix)
        return new GMMValue(K, 1, "full", weights, means, covariances);
    }
    
    /**
     * Extract covariance element for marginal dimension.
     */
    private double[][] extractCovarianceElement(double[][] cov, int dim, 
                                                String covType, int d) {
        double variance;
        
        switch (covType) {
            case "full":
                variance = cov[dim][dim];
                break;
                
            case "diag":
                variance = cov[0][dim];
                break;
                
            case "spherical":
                variance = cov[0][0]; // Same for all dimensions
                break;
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
        
        return new double[][] {{variance}};
    }
}
