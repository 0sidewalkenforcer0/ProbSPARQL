package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function to count the number of modes (components) in a GMM.
 * 
 * <p>Returns K, the number of Gaussian components in the mixture.</p>
 * 
 * <p>This represents an upper bound on the number of local modes
 * in the probability distribution.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?numModes WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:modeCount(?gmm) AS ?numModes)
 *   FILTER(?numModes > 1)  # Find multimodal distributions
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class ModeCount extends FunctionBase1 {
    
    public static final String URI = "http://probsparql.org/function#modeCount";
    
    /**
     * Count the number of components (modes) in a GMM.
     * 
     * @param gmmNode GMM distribution
     * @return NodeValue containing integer K
     */
    @Override
    public NodeValue exec(NodeValue gmmNode) {
        GMMValue gmm = extractGMM(gmmNode);
        
        int K = gmm.getNComponents();
        
        return NodeValue.makeInteger(K);
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
}
