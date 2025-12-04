package org.apache.jena.sparql.algebra;

import org.apache.jena.sparql.algebra.op.OpFuseJoin;

/**
 * Transform interface extension for probabilistic algebra operators.
 * Extends standard Transform to support custom probabilistic operations.
 */
public interface TransformProbabilistic extends Transform {
    /**
     * Transform a FuseJoin operator.
     * @param opFuseJoin The fusion join operator
     * @param left The transformed left sub-operator
     * @param right The transformed right sub-operator
     * @return The transformed operator
     */
    Op transform(OpFuseJoin opFuseJoin, Op left, Op right);
}
