package org.apache.jena.sparql.engine.main;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.algebra.op.OpSimilarityJoin;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.iterator.QueryIterFuseJoin;
import org.apache.jena.sparql.engine.iterator.QueryIterFuseJoinFilter;
import org.apache.jena.sparql.engine.iterator.QueryIterSimilarityJoin;
import org.apache.jena.sparql.engine.iterator.QueryIterSimilarityJoinFilter;

/**
 * OpExecutor extension for probabilistic operators.
 * Handles execution of custom algebra operators like OpFuseJoin and OpSimilarityJoin.
 * 
 * Updated for new relational semantics (dual-input nested loop join):
 * { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
 * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
 */
public class OpExecutorProbabilistic extends OpExecutor {
    
    public OpExecutorProbabilistic(ExecutionContext execCxt) {
        super(execCxt);
    }
    
    /**
     * Override exec to intercept OpFuseJoin and OpSimilarityJoin BEFORE the visitor pattern processes them.
     * This is necessary because these operators' visit() methods delegate to subOp, bypassing themselves.
     */
    @Override
    protected QueryIterator exec(Op op, QueryIterator input) {
        // Intercept OpFuseJoin before dispatcher sees it
        if (op instanceof OpFuseJoin) {
            return execute((OpFuseJoin) op, input);
        }
        
        // Intercept OpSimilarityJoin before dispatcher sees it
        if (op instanceof OpSimilarityJoin) {
            return execute((OpSimilarityJoin) op, input);
        }
        
        // Delegate to parent for standard operators (which uses dispatcher)
        return super.exec(op, input);
    }
    
    public static OpExecutorFactory factory = new OpExecutorFactory() {
        @Override
        public OpExecutor create(ExecutionContext execCxt) {
            return new OpExecutorProbabilistic(execCxt);
        }
    };
    
    /**
     * Execute OpFuseJoin.
     * 
     * Two modes are supported:
     * 1. Legacy mode (leftOp == rightOp): Filter mode - both variables are in same binding
     * 2. New mode (leftOp != rightOp): Nested loop join - left and right are independent patterns
     * 
     * Legacy mode: FUSEJOIN(?d1, ?d2, tolerance, ?result) { }
     * - All variables bound in single BGP
     * - Apply JS divergence filter and fuse distributions
     * 
     * New mode: { leftPattern } FUSEJOIN(?d1, ?d2, tolerance, ?result) { rightPattern }
     * - Execute left and right patterns independently
     * - Nested loop join with JS divergence predicate, fuse on match
     */
    public QueryIterator execute(OpFuseJoin opFuseJoin, QueryIterator input) {
        // Get left and right operations
        Op leftOp = opFuseJoin.getLeftOp();
        Op rightOp = opFuseJoin.getRightOp();
        
        // Extract parameters
        Var leftVar = opFuseJoin.getLeftVarObj();
        Var rightVar = opFuseJoin.getRightVarObj();
        double tolerance = opFuseJoin.getTolerance();
        Var resultVar = opFuseJoin.getResultVarObj();
        
        // Get legacy mode flag from the operator
        boolean isLegacyMode = opFuseJoin.isLegacyMode();
        
        if (isLegacyMode) {
            // Legacy mode: Execute the single BGP and filter results by JS divergence
            // Input contains root binding; execute BGP to get all bindings with both variables
            QueryIterator bgpResults = executeOp(leftOp, input);
            
            // Apply JS divergence filter with fusion
            return new QueryIterFuseJoinFilter(
                bgpResults,
                leftVar,
                rightVar,
                tolerance,
                resultVar,
                execCxt
            );
        } else {
            // New mode: Nested loop join between left and right patterns
            // Execute left pattern to get left table bindings
            QueryIterator leftInput = executeOp(leftOp, input);
            
            // Create nested loop join iterator
            // The iterator will execute rightOp internally
            return new QueryIterFuseJoin(
                leftInput,
                rightOp,
                leftVar,
                rightVar,
                tolerance,
                resultVar,
                execCxt
            );
        }
    }
    
    /**
     * Execute OpSimilarityJoin.
     * 
     * Two modes are supported:
     * 1. Legacy mode (leftOp == rightOp): Filter mode - both variables are in same binding
     * 2. New mode (leftOp != rightOp): Nested loop join - left and right are independent patterns
     * 
     * Legacy mode: SIMILARITYJOIN(?d1, ?d2, tolerance) { }
     * - All variables bound in single BGP
     * - Apply JS divergence filter to existing bindings
     * 
     * New mode: { leftPattern } SIMILARITYJOIN(?d1, ?d2, tolerance) { rightPattern }
     * - Execute left and right patterns independently
     * - Nested loop join with JS divergence predicate
     */
    public QueryIterator execute(OpSimilarityJoin opSimilarityJoin, QueryIterator input) {
        // Get left and right operations
        Op leftOp = opSimilarityJoin.getLeftOp();
        Op rightOp = opSimilarityJoin.getRightOp();
        
        // Extract parameters
        Var leftVar = opSimilarityJoin.getLeftVar();
        Var rightVar = opSimilarityJoin.getRightVar();
        double tolerance = opSimilarityJoin.getTolerance();
        
        // Get legacy mode flag from the operator
        boolean isLegacyMode = opSimilarityJoin.isLegacyMode();
        
        if (isLegacyMode) {
            // Legacy mode: Execute the single BGP and filter results by JS divergence
            // Input contains root binding; execute BGP to get all bindings with both variables
            QueryIterator bgpResults = executeOp(leftOp, input);
            
            // Apply JS divergence filter
            return new QueryIterSimilarityJoinFilter(
                bgpResults,
                leftVar,
                rightVar,
                tolerance,
                execCxt
            );
        } else {
            // New mode: Nested loop join between left and right patterns
            // Execute left pattern to get left table bindings
            QueryIterator leftInput = executeOp(leftOp, input);
            
            // Create nested loop join iterator
            // The iterator will execute rightOp internally
            return new QueryIterSimilarityJoin(
                leftInput,
                rightOp,
                leftVar,
                rightVar,
                tolerance,
                execCxt
            );
        }
    }
    
    @Override
    public QueryIterator executeOp(Op op, QueryIterator input) {
        // Handle OpFuseJoin
        if (op instanceof OpFuseJoin) {
            return execute((OpFuseJoin) op, input);
        }
        
        // Handle OpSimilarityJoin
        if (op instanceof OpSimilarityJoin) {
            return execute((OpSimilarityJoin) op, input);
        }
        
        // Delegate to parent for standard operators
        return super.executeOp(op, input);
    }
}
