package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.probsparql.functions.DistributionSupport;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the Probability Density Function (PDF)
 * of a supported probabilistic distribution at a given point.
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?density WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:pdf(?gmm, 6.0) AS ?density)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class PDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#pdf";
    
    /**
     * Evaluate PDF at a point.
     * 
     * @param distNode NodeValue containing a supported distribution literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return PDF value as double
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue pointNode) {
        Sampleable distribution = DistributionSupport.extractSampleable(distNode, "prob:pdf");
        double[] point = DistributionSupport.extractPoint(
            pointNode, DistributionSupport.dimensions(distribution), "prob:pdf");
        return NodeValue.makeDouble(Math.exp(distribution.logPdf(point)));
    }
}
