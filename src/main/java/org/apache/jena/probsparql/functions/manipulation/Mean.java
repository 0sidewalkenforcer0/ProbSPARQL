package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function to compute the mean (expected value) of a GMM.
 * 
 * <p>For a GMM X ~ Σ w_i * N(μ_i, Σ_i), the expected value is:</p>
 * <pre>
 * E[X] = Σ w_i * μ_i
 * </pre>
 * 
 * <p>This is the weighted average of component means.</p>
 * 
 * <p>Returns a JSON string representing the mean vector:</p>
 * <ul>
 *   <li>1D: "[1.5]" or "1.5"</li>
 *   <li>Multi-D: "[1.5, 2.3, 0.8]"</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?meanValue WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:mean(?gmm) AS ?meanValue)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Mean extends FunctionBase1 {
    
    public static final String URI = "http://probsparql.org/function#mean";
    
    /**
     * Compute the mean (expected value) of a GMM.
     * 
     * @param gmmNode GMM distribution
     * @return NodeValue containing JSON string of mean vector
     */
    @Override
    public NodeValue exec(NodeValue gmmNode) {
        GMMValue gmm = extractGMM(gmmNode);
        
        double[] mean = computeMean(gmm);
        
        // Return as JSON string
        String meanJson = formatVector(mean);
        return NodeValue.makeString(meanJson);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "Argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "Argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Compute mean as weighted average of component means.
     * 
     * E[X] = Σ w_i * μ_i
     */
    private double[] computeMean(GMMValue gmm) {
        int K = gmm.getK();
        int d = gmm.getD();
        
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        
        double[] mean = new double[d];
        
        // Weighted sum of component means
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < d; j++) {
                mean[j] += weights[i] * means[i][j];
            }
        }
        
        return mean;
    }
    
    /**
     * Format vector as JSON string.
     * 
     * 1D: "[1.5]"
     * Multi-D: "[1.5, 2.3, 0.8]"
     */
    private String formatVector(double[] vector) {
        if (vector.length == 1) {
            return String.format("[%.6f]", vector[0]);
        }
        
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        
        return sb.toString();
    }
}
