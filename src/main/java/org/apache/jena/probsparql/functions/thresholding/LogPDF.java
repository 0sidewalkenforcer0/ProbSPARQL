package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.probsparql.functions.DistributionSupport;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the logarithm of Probability Density Function (Log-PDF) 
 * of a supported probabilistic distribution at a given point.
 * 
 * <p>Log-PDF is numerically more stable than PDF for very small probabilities
 * and is commonly used in machine learning and statistics.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?logDensity WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:logpdf(?gmm, 6.0) AS ?logDensity)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class LogPDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#logpdf";
    
    /**
     * Evaluate Log-PDF at a point.
     * 
     * @param distNode NodeValue containing a supported distribution literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return Log-PDF value as double
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue pointNode) {
        Sampleable distribution = DistributionSupport.extractSampleable(distNode, "prob:logpdf");
        double[] point = DistributionSupport.extractPoint(
            pointNode, DistributionSupport.dimensions(distribution), "prob:logpdf");
        return NodeValue.makeDouble(distribution.logPdf(point));
    }
}
