package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function to compute the MAP (Maximum A Posteriori) estimate of a GMM.
 * 
 * <p>Returns the mean of the component with the highest weight:</p>
 * <pre>
 * MAP = μ_k where k = argmax_i w_i
 * </pre>
 * 
 * <p>This represents the most likely value under the GMM distribution.</p>
 * 
 * <p>Returns a JSON string:</p>
 * <ul>
 *   <li>1D: "[1.5]"</li>
 *   <li>Multi-D: "[1.5, 2.3, 0.8]"</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?mapValue WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:map(?gmm) AS ?mapValue)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Map extends FunctionBase1 {
    
    public static final String URI = "http://probsparql.org/function#map";
    
    /**
     * Compute the MAP estimate of a GMM.
     * 
     * @param gmmNode GMM distribution
     * @return NodeValue containing JSON string of MAP estimate
     */
    @Override
    public NodeValue exec(NodeValue gmmNode) {
        GMMValue gmm = extractGMM(gmmNode);
        
        double[] mapEstimate = computeMAP(gmm);
        
        // Return as JSON string
        String mapJson = formatVector(mapEstimate);
        return NodeValue.makeString(mapJson);
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
     * Compute MAP estimate as mean of component with maximum weight.
     */
    private double[] computeMAP(GMMValue gmm) {
        int K = gmm.getK();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        
        // Find component with maximum weight
        int maxIdx = 0;
        double maxWeight = weights[0];
        
        for (int i = 1; i < K; i++) {
            if (weights[i] > maxWeight) {
                maxWeight = weights[i];
                maxIdx = i;
            }
        }
        
        // Return mean of that component
        return means[maxIdx];
    }
    
    /**
     * Format vector as JSON string.
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
