package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the product of two independent 1-D GMMs.
 * 
 * <p>For Z = X * Y where X ~ GMM1 and Y ~ GMM2 are independent 1-D distributions,
 * this function computes an approximation using moment matching:</p>
 * <ul>
 *   <li>E[Z] = E[X] * E[Y] (product of means)</li>
 *   <li>Var[Z] ≈ μ²_X * Var[Y] + μ²_Y * Var[X] + Var[X] * Var[Y] (first-order approximation)</li>
 * </ul>
 * 
 * <p>The result is a single Gaussian (K=1) that approximates the product distribution.
 * This is commonly used for uncertainty propagation in multiplicative operations,
 * such as computing power from speed × torque.</p>
 * 
 * <p><b>Limitations:</b></p>
 * <ul>
 *   <li>Only supports 1-D GMMs (d=1)</li>
 *   <li>Uses first-order approximation (may be inaccurate for large uncertainties)</li>
 *   <li>Returns a single Gaussian approximation</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * PREFIX uq: &lt;http://example.org/ontology/uncertainty#&gt;
 * 
 * SELECT ?motor ?power WHERE {
 *   ?motor ag:hasSpeedCharacteristic ?speedChar ;
 *          ag:hasTorqueCharacteristic ?torqueChar .
 *   
 *   ?speedMeasure cfm:measuresCharacteristic ?speedChar ;
 *                 cfm:hasProbabilisticValue ?rvSpeed .
 *   ?rvSpeed uq:hasDistribution ?gmmSpeed .
 *   
 *   ?torqueMeasure cfm:measuresCharacteristic ?torqueChar ;
 *                  cfm:hasProbabilisticValue ?rvTorque .
 *   ?rvTorque uq:hasDistribution ?gmmTorque .
 *   
 *   # Compute power distribution: P = ω × τ
 *   BIND(prob:multiply(?gmmSpeed, ?gmmTorque) AS ?power)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Multiply extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#multiply";
    
    /**
     * Compute product of two independent 1-D GMMs using moment matching.
     * 
     * @param gmm1Node First GMM (X)
     * @param gmm2Node Second GMM (Y)
     * @return GMM approximation of Z = X * Y
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        
        // Validate 1-D constraint
        if (gmm1.getD() != 1 || gmm2.getD() != 1) {
            throw new IllegalArgumentException(
                "multiply() only supports 1-D GMMs. Got d1=" + gmm1.getD() + 
                ", d2=" + gmm2.getD() + ". For multi-dimensional multiplication, " +
                "use prob:joint() to create joint distribution.");
        }
        
        GMMValue productGMM = computeProduct(gmm1, gmm2);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            productGMM.toJSON(),
            GMMDatatype.INSTANCE
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
     * Compute product Z = X * Y using moment matching approximation.
     * 
     * <p>For independent X and Y:</p>
     * <ul>
     *   <li>E[Z] = E[X] * E[Y]</li>
     *   <li>Var[Z] ≈ μ²_X * Var[Y] + μ²_Y * Var[X] + Var[X] * Var[Y]</li>
     * </ul>
     * 
     * <p>This is the first-order Taylor approximation (delta method).
     * For more accuracy with large uncertainties, Monte Carlo sampling
     * would be needed, but this approximation is sufficient for most
     * engineering applications where uncertainty is small (&lt;10% CV).</p>
     */
    private GMMValue computeProduct(GMMValue gmm1, GMMValue gmm2) {
        // Compute moments of input GMMs
        double mu1 = computeMean(gmm1);
        double mu2 = computeMean(gmm2);
        double var1 = computeVariance(gmm1, mu1);
        double var2 = computeVariance(gmm2, mu2);
        
        // Product moments using first-order approximation
        double muProduct = mu1 * mu2;
        
        // Var[XY] = μ²_X Var[Y] + μ²_Y Var[X] + Var[X]Var[Y]
        // Simplified: For small CV, last term (Var[X]Var[Y]) is often negligible
        double varProduct = mu1 * mu1 * var2 + mu2 * mu2 * var1 + var1 * var2;
        
        // Return single Gaussian (K=1, d=1)
        double[] weights = {1.0};
        double[][] means = {{muProduct}};
        double[][][] covariances = {{{varProduct}}};
        
        return new GMMValue(1, 1, "full", weights, means, covariances);
    }
    
    /**
     * Compute mean of 1-D GMM: E[X] = Σ w_i * μ_i
     */
    private double computeMean(GMMValue gmm) {
        double mean = 0.0;
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        
        for (int k = 0; k < gmm.getK(); k++) {
            mean += weights[k] * means[k][0];
        }
        
        return mean;
    }
    
    /**
     * Compute variance of 1-D GMM: Var[X] = E[X²] - E[X]²
     * where E[X²] = Σ w_i * (σ²_i + μ²_i)
     */
    private double computeVariance(GMMValue gmm, double mean) {
        double secondMoment = 0.0;
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        
        for (int k = 0; k < gmm.getK(); k++) {
            double mu_k = means[k][0];
            double sigma2_k = covariances[k][0][0]; // Variance of component k
            
            // E[X²] contribution from component k: w_k * (σ²_k + μ²_k)
            secondMoment += weights[k] * (sigma2_k + mu_k * mu_k);
        }
        
        // Var[X] = E[X²] - E[X]²
        return secondMoment - mean * mean;
    }
}
