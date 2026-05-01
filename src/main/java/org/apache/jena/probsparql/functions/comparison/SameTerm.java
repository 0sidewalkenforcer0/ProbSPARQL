package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.expr.nodevalue.NodeFunctions;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:sameTerm} for explicit RDF-term equality.
 *
 * <p>This follows term identity rather than datatype value equality, so two
 * literals with different lexical forms remain distinct even if they denote the
 * same probabilistic distribution after parsing.</p>
 */
public class SameTerm extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#sameTerm";

    @Override
    public NodeValue exec(NodeValue left, NodeValue right) {
        return NodeFunctions.sameTerm(left, right);
    }
}
