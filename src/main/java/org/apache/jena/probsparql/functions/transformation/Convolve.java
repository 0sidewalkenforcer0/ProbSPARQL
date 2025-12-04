package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the convolution of two GMMs.
 * 
 * <p>For Z = X + Y where X ~ GMM1 and Y ~ GMM2 are independent,
 * the sum Z follows a GMM with:</p>
 * <ul>
 *   <li>K = K1 * K2 components</li>
 *   <li>Weights: w_ij = w1_i * w2_j</li>
 *   <li>Means: μ_ij = μ1_i + μ2_j</li>
 *   <li>Covariances: Σ_ij = Σ1_i + Σ2_j</li>
 * </ul>
 * 
 * <p>This represents the distribution of the sum of two independent
 * random variables, which is fundamental for uncertainty propagation.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?sumDist WHERE {
 *   ?var1 uq:hasDistribution ?gmm1 .
 *   ?var2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:convolve(?gmm1, ?gmm2) AS ?sumDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Convolve extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#convolve";
    
    /**
     * Compute convolution (sum) of two independent GMMs.
     * 
     * @param gmm1Node First GMM (X)
     * @param gmm2Node Second GMM (Y)
     * @return Convolution GMM representing Z = X + Y
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        
        // Validate compatibility
        if (gmm1.getD() != gmm2.getD()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality for convolution. Got d1=" + 
                gmm1.getD() + ", d2=" + gmm2.getD());
        }
        
        GMMValue convolvedGMM = computeConvolution(gmm1, gmm2);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            convolvedGMM.toJSON(), GMMDatatype.INSTANCE
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
     * Compute convolution of two GMMs.
     * 
     * Z = X + Y => Each pair (i,j) creates component with:
     * - Weight: w_ij = w1_i * w2_j
     * - Mean: μ_ij = μ1_i + μ2_j
     * - Covariance: Σ_ij = Σ1_i + Σ2_j
     */
    private GMMValue computeConvolution(GMMValue gmm1, GMMValue gmm2) {
        int K1 = gmm1.getK();
        int K2 = gmm2.getK();
        int d = gmm1.getD();
        
        int K = K1 * K2;
        
        double[] weights = new double[K];
        double[][] means = new double[K][d];
        double[][][] covariances = new double[K][][];
        
        int idx = 0;
        for (int i = 0; i < K1; i++) {
            for (int j = 0; j < K2; j++) {
                // Weight: product of component weights
                weights[idx] = gmm1.getWeights()[i] * gmm2.getWeights()[j];
                
                // Mean: sum of component means
                means[idx] = addVectors(
                    gmm1.getMeans()[i], 
                    gmm2.getMeans()[j], 
                    d
                );
                
                // Covariance: sum of component covariances
                covariances[idx] = addCovariances(
                    gmm1.getCovariances()[i],
                    gmm2.getCovariances()[j],
                    gmm1.getCovarianceType(),
                    gmm2.getCovarianceType(),
                    d
                );
                
                idx++;
            }
        }
        
        // Result uses full covariance type (most general)
        return new GMMValue(K, d, "full", weights, means, covariances);
    }
    
    /**
     * Add two vectors element-wise.
     */
    private double[] addVectors(double[] v1, double[] v2, int d) {
        double[] result = new double[d];
        for (int i = 0; i < d; i++) {
            result[i] = v1[i] + v2[i];
        }
        return result;
    }
    
    /**
     * Add two covariance matrices.
     */
    private double[][] addCovariances(double[][] cov1, double[][] cov2,
                                      String type1, String type2, int d) {
        // Convert both to full form
        double[][] full1 = toFullCovariance(cov1, type1, d);
        double[][] full2 = toFullCovariance(cov2, type2, d);
        
        // Add matrices element-wise
        double[][] result = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                result[i][j] = full1[i][j] + full2[i][j];
            }
        }
        
        return result;
    }
    
    /**
     * Convert covariance to full matrix form.
     */
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        switch (type) {
            case "full":
                return cov;
                
            case "diag":
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? cov[i][0] : 0.0;
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
