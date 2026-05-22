package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the logarithm of Cumulative Distribution Function (Log-CDF) 
 * of a supported probabilistic distribution at a given point.
 * 
 * <p>Log-CDF is numerically more stable than CDF for very small probabilities.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?logProbability WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:logcdf(?gmm, 6.0) AS ?logProbability)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class LogCDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#logcdf";

    /**
     * Evaluate Log-CDF at a point.
     * 
     * @param distNode NodeValue containing a supported distribution literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return Log-CDF value as double
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue pointNode) {
        double cdf = new CDF().exec(distNode, pointNode).getDouble();
        if (cdf <= 0.0) {
            return NodeValue.makeDouble(Double.NEGATIVE_INFINITY);
        }
        return NodeValue.makeDouble(Math.log(cdf));
    }
}
