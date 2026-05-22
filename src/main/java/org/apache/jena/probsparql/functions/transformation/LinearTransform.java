package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramOperations;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase3;

/**
 * SPARQL function to apply a linear transformation to a GMM or Histogram.
 * 
 * <p>For Y = a * X + b where X is a GMM, the result is:</p>
 * <ul>
 *   <li>Means: μ_Y = a * μ_X + b</li>
 *   <li>Covariances: Σ_Y = a² * Σ_X</li>
 *   <li>Weights: unchanged</li>
 * </ul>
 * 
 * <p>For a Histogram, the affine transform is applied to bin edges while
 * preserving probability masses.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?transformedDist WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:linearTransform(?gmm, 2.0, 3.0) AS ?transformedDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class LinearTransform extends FunctionBase3 {
    
    public static final String URI = "http://probsparql.org/function#linearTransform";
    
    /**
     * Apply linear transformation Y = a*X + b to a GMM or Histogram.
     * 
     * @param distNode NodeValue containing a GMM or Histogram literal
     * @param scaleNode NodeValue containing scale factor a
     * @param offsetNode NodeValue containing offset b
     * @return Transformed distribution literal of the same datatype
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue scaleNode, NodeValue offsetNode) {
        double a = extractNumeric(scaleNode, "scale factor");
        double b = extractNumeric(offsetNode, "offset");

        Object value = distNode.asNode().getLiteralValue();
        if (value instanceof HistogramValue histogram) {
            HistogramValue transformed = HistogramOperations.affine(histogram, a, b);
            org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
                transformed.toString(), HistogramDatatype.INSTANCE
            );
            return NodeValue.makeNode(node);
        }

        GMMValue gmm = extractGMM(distNode);
        GMMValue transformedGMM = linearTransform(gmm, a, b);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            transformedGMM.toJSON(), GMMDatatype.INSTANCE
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
     * Extract numeric value from NodeValue.
     */
    private double extractNumeric(NodeValue node, String paramName) {
        if (!node.isNumber()) {
            throw new IllegalArgumentException(
                "Parameter '" + paramName + "' must be numeric");
        }
        
        return node.getDouble();
    }
    
    /**
     * Apply linear transformation Y = a*X + b.
     * 
     * μ_Y = a * μ_X + b
     * Σ_Y = a² * Σ_X
     */
    private GMMValue linearTransform(GMMValue gmm, double a, double b) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String covType = gmm.getCovarianceType();
        
        double[] weights = gmm.getWeights().clone();
        
        // Transform means: μ_Y = a * μ_X + b
        double[][] means = new double[K][d];
        for (int k = 0; k < K; k++) {
            for (int i = 0; i < d; i++) {
                means[k][i] = a * gmm.getMeans()[k][i] + b;
            }
        }
        
        // Transform covariances: Σ_Y = a² * Σ_X
        double[][][] covariances = new double[K][][];
        double a2 = a * a;
        
        for (int k = 0; k < K; k++) {
            covariances[k] = scaleCovariance(gmm.getCovariances()[k], a2, covType, d);
        }
        
        return new GMMValue(K, d, covType, weights, means, covariances);
    }
    
    /**
     * Scale covariance matrix by a².
     */
    private double[][] scaleCovariance(double[][] cov, double a2, String covType, int d) {
        switch (covType) {
            case "full":
                double[][] fullCov = new double[d][d];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        fullCov[i][j] = a2 * cov[i][j];
                    }
                }
                return fullCov;
                
            case "diag":
                double[][] diagCov = new double[1][d];
                for (int i = 0; i < d; i++) {
                    diagCov[0][i] = a2 * cov[0][i];
                }
                return diagCov;
                
            case "spherical":
                return new double[][] {{a2 * cov[0][0]}};
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }
}
