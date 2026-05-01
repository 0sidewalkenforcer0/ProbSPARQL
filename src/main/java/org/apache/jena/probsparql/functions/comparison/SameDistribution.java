package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:sameDistribution} for value-based equality.
 *
 * <p>This delegates to the same value-equality semantics as SPARQL {@code =},
 * allowing datatype-specific parsed values to decide whether two distribution
 * literals denote the same value.</p>
 */
public class SameDistribution extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#sameDistribution";

    @Override
    public NodeValue exec(NodeValue left, NodeValue right) {
        return NodeValue.booleanReturn(left.asNode().sameValueAs(right.asNode()));
    }
}
