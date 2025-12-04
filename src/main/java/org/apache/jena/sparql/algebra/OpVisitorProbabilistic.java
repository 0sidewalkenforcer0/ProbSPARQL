package org.apache.jena.sparql.algebra;

import org.apache.jena.sparql.algebra.op.OpFuseJoin;

/**
 * Visitor interface extension for probabilistic algebra operators.
 * Extends standard OpVisitor to support custom probabilistic operations.
 */
public interface OpVisitorProbabilistic extends OpVisitor {
    /**
     * Visit a FuseJoin operator.
     * @param opFuseJoin The fusion join operator to visit
     */
    void visit(OpFuseJoin opFuseJoin);
}
