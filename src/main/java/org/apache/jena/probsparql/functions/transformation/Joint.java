package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the joint distribution of two independent GMMs.
 * 
 * <p>For independent random variables X ~ GMM1 and Y ~ GMM2, 
 * the joint distribution (X, Y) is a GMM with:</p>
 * <ul>
 *   <li>K = K1 * K2 components (product of component counts)</li>
 *   <li>d = d1 + d2 dimensions (concatenation)</li>
 *   <li>Weights: w_ij = w1_i * w2_j</li>
 *   <li>Means: μ_ij = [μ1_i; μ2_j] (concatenation)</li>
 *   <li>Covariances: Σ_ij = block-diagonal [Σ1_i, 0; 0, Σ2_j]</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?jointDist WHERE {
 *   ?var1 uq:hasDistribution ?gmm1 .
 *   ?var2 uq:hasDistribution ?gmm2 .
 *   BIND(prob:joint(?gmm1, ?gmm2) AS ?jointDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Joint extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#joint";
    
    /**
     * Compute joint distribution of two independent GMMs.
     * 
     * @param gmm1Node First GMM
     * @param gmm2Node Second GMM
     * @return Joint GMM with d1+d2 dimensions
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        
        GMMValue jointGMM = computeJoint(gmm1, gmm2);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            jointGMM.toJSON(), GMMDatatype.INSTANCE
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
     * Compute joint distribution of independent GMMs.
     */
    private GMMValue computeJoint(GMMValue gmm1, GMMValue gmm2) {
        int K1 = gmm1.getK();
        int K2 = gmm2.getK();
        int d1 = gmm1.getD();
        int d2 = gmm2.getD();
        
        int K = K1 * K2;
        int d = d1 + d2;
        
        double[] weights = new double[K];
        double[][] means = new double[K][d];
        double[][][] covariances = new double[K][][];
        
        int idx = 0;
        for (int i = 0; i < K1; i++) {
            for (int j = 0; j < K2; j++) {
                // Weight: w_ij = w1_i * w2_j
                weights[idx] = gmm1.getWeights()[i] * gmm2.getWeights()[j];
                
                // Mean: [μ1_i; μ2_j]
                means[idx] = concatenateMeans(
                    gmm1.getMeans()[i], 
                    gmm2.getMeans()[j], 
                    d1, d2
                );
                
                // Covariance: block diagonal
                covariances[idx] = blockDiagonalCovariance(
                    gmm1.getCovariances()[i], 
                    gmm2.getCovariances()[j],
                    gmm1.getCovarianceType(),
                    gmm2.getCovarianceType(),
                    d1, d2
                );
                
                idx++;
            }
        }
        
        // Joint uses full covariance (block diagonal structure)
        return new GMMValue(K, d, "full", weights, means, covariances);
    }
    
    /**
     * Concatenate two mean vectors.
     */
    private double[] concatenateMeans(double[] mean1, double[] mean2, int d1, int d2) {
        double[] result = new double[d1 + d2];
        
        System.arraycopy(mean1, 0, result, 0, d1);
        System.arraycopy(mean2, 0, result, d1, d2);
        
        return result;
    }
    
    /**
     * Create block diagonal covariance matrix.
     * 
     * [Σ1   0 ]
     * [0   Σ2]
     */
    private double[][] blockDiagonalCovariance(double[][] cov1, double[][] cov2,
                                               String type1, String type2,
                                               int d1, int d2) {
        int d = d1 + d2;
        double[][] result = new double[d][d];
        
        // Initialize all to zero
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                result[i][j] = 0.0;
            }
        }
        
        // Fill top-left block with cov1
        double[][] fullCov1 = toFullCovariance(cov1, type1, d1);
        for (int i = 0; i < d1; i++) {
            for (int j = 0; j < d1; j++) {
                result[i][j] = fullCov1[i][j];
            }
        }
        
        // Fill bottom-right block with cov2
        double[][] fullCov2 = toFullCovariance(cov2, type2, d2);
        for (int i = 0; i < d2; i++) {
            for (int j = 0; j < d2; j++) {
                result[d1 + i][d1 + j] = fullCov2[i][j];
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
