package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function to compute the standard deviation of a GMM.
 * 
 * <p>For a GMM X ~ Σ w_i * N(μ_i, Σ_i), the variance is:</p>
 * <pre>
 * Var[X] = E[X²] - E[X]² = Σ w_i * (Σ_i + μ_i*μ_i') - E[X]*E[X]'
 * </pre>
 * 
 * <p>Returns the square root of diagonal elements of the covariance matrix
 * as a JSON string:</p>
 * <ul>
 *   <li>1D: "[0.5]"</li>
 *   <li>Multi-D: "[0.5, 0.8, 1.2]" (standard deviation for each dimension)</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?stdDev WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:std(?gmm) AS ?stdDev)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Std extends FunctionBase1 {
    
    public static final String URI = "http://probsparql.org/function#std";
    
    /**
     * Compute the standard deviation of a GMM.
     * 
     * @param gmmNode GMM distribution
     * @return NodeValue containing JSON string of standard deviations
     */
    @Override
    public NodeValue exec(NodeValue gmmNode) {
        GMMValue gmm = extractGMM(gmmNode);
        
        double[] variance = computeVariance(gmm);
        
        // Convert variance to standard deviation
        double[] std = new double[variance.length];
        for (int i = 0; i < variance.length; i++) {
            std[i] = Math.sqrt(variance[i]);
        }
        
        // Return as JSON string
        String stdJson = formatVector(std);
        return NodeValue.makeString(stdJson);
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
     * Compute variance (diagonal elements) using the formula:
     * Var[X] = E[X²] - E[X]²
     *        = Σ w_i * (diag(Σ_i) + μ_i²) - μ²
     */
    private double[] computeVariance(GMMValue gmm) {
        int K = gmm.getK();
        int d = gmm.getD();
        
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        // Compute E[X]
        double[] mean = new double[d];
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < d; j++) {
                mean[j] += weights[i] * means[i][j];
            }
        }
        
        // Compute E[X²] = Σ w_i * (diag(Σ_i) + μ_i²)
        double[] secondMoment = new double[d];
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < d; j++) {
                // Get diagonal element of covariance
                double covDiag = getCovarianceDiagonal(covariances[i], covType, j, d);
                
                // E[X²] component: w_i * (Σ_ii + μ_i²)
                secondMoment[j] += weights[i] * (covDiag + means[i][j] * means[i][j]);
            }
        }
        
        // Var[X] = E[X²] - E[X]²
        double[] variance = new double[d];
        for (int i = 0; i < d; i++) {
            variance[i] = secondMoment[i] - mean[i] * mean[i];
            
            // Ensure non-negative due to numerical errors
            if (variance[i] < 0) {
                variance[i] = 0;
            }
        }
        
        return variance;
    }
    
    /**
     * Get diagonal element of covariance matrix.
     */
    private double getCovarianceDiagonal(double[][] cov, String type, int idx, int d) {
        switch (type) {
            case "full":
                return cov[idx][idx];
                
            case "diag":
                return cov[idx][0];
                
            case "spherical":
                return cov[0][0];
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + type);
        }
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
