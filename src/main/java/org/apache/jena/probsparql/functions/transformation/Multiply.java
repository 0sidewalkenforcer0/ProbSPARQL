package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the product of two independent GMMs.
 * 
 * <p>For Z = X * Y where X ~ GMM1 and Y ~ GMM2 are independent distributions,
 * this function computes an approximation using moment matching.</p>
 * <ul>
 *   <li>E[Z] = E[X] * E[Y] (product of means)</li>
 *   <li>Var[Z] ≈ μ²_X * Var[Y] + μ²_Y * Var[X] + Var[X] * Var[Y] (first-order approximation)</li>
 * </ul>
 * 
 * <p>The result is a single Gaussian component (K=1) that approximates the product distribution.
 * This is commonly used for uncertainty propagation in multiplicative operations,
 * such as computing power from speed × torque.</p>
 * 
 * <p><b>Limitations:</b></p>
 * <ul>
 *   <li>Requires both GMMs to have the same dimensionality</li>
 *   <li>Uses first-order approximation (may be inaccurate for large uncertainties)</li>
 *   <li>Returns a single diagonal Gaussian approximation</li>
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
     * Compute product of two independent GMMs using moment matching.
     * 
     * @param gmm1Node First GMM (X)
     * @param gmm2Node Second GMM (Y)
     * @return GMM approximation of Z = X * Y
     */
    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        
        if (gmm1.getDimensions() != gmm2.getDimensions()) {
            throw new IllegalArgumentException(
                "multiply() requires matching dimensions. Got d1=" + gmm1.getDimensions() +
                ", d2=" + gmm2.getDimensions());
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
        int d = gmm1.getDimensions();
        double[] mu1 = computeMeanVector(gmm1);
        double[] mu2 = computeMeanVector(gmm2);
        double[] var1 = computeVarianceVector(gmm1, mu1);
        double[] var2 = computeVarianceVector(gmm2, mu2);

        double[] muProduct = new double[d];
        double[] varProduct = new double[d];
        for (int j = 0; j < d; j++) {
            muProduct[j] = mu1[j] * mu2[j];
            varProduct[j] = mu1[j] * mu1[j] * var2[j] + mu2[j] * mu2[j] * var1[j] + var1[j] * var2[j];
        }

        double[] weights = {1.0};
        double[][] means = {muProduct};
        double[][][] covariances = {{varProduct}};
        return new GMMValue(1, d, "diag", weights, means, covariances);
    }
    
    /**
     * Compute per-dimension mean vector of a GMM.
     */
    private double[] computeMeanVector(GMMValue gmm) {
        int d = gmm.getDimensions();
        double[] mean = new double[d];
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        
        for (int k = 0; k < gmm.getNComponents(); k++) {
            for (int j = 0; j < d; j++) {
                mean[j] += weights[k] * means[k][j];
            }
        }
        
        return mean;
    }
    
    /**
     * Compute per-dimension marginal variances of a GMM.
     */
    private double[] computeVarianceVector(GMMValue gmm, double[] mean) {
        int d = gmm.getDimensions();
        double[] secondMoment = new double[d];
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        
        for (int k = 0; k < gmm.getNComponents(); k++) {
            for (int j = 0; j < d; j++) {
                double mu_k = means[k][j];
                double sigma2_k = marginalVariance(gmm, covariances, k, j);
                secondMoment[j] += weights[k] * (sigma2_k + mu_k * mu_k);
            }
        }

        double[] variance = new double[d];
        for (int j = 0; j < d; j++) {
            variance[j] = secondMoment[j] - mean[j] * mean[j];
        }
        return variance;
    }

    private double marginalVariance(GMMValue gmm, double[][][] covariances, int component, int dim) {
        return switch (gmm.getCovarianceType()) {
            case "diag", "spherical" -> covariances[component][0][Math.min(dim, covariances[component][0].length - 1)];
            case "full" -> covariances[component][dim][dim];
            default -> throw new IllegalArgumentException("Unsupported covariance type: " + gmm.getCovarianceType());
        };
    }
}
