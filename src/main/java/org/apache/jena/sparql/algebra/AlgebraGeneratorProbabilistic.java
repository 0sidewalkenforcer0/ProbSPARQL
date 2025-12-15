package org.apache.jena.sparql.algebra;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.algebra.op.OpSimilarityJoin;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.*;
import org.apache.jena.sparql.util.Context;

import java.util.Deque;

/**
 * Algebra generator extension for probabilistic syntax elements.
 * Extends Jena's AlgebraGenerator to handle ElementFuseJoin and ElementSimilarityJoin.
 * 
 * This generator integrates seamlessly with Jena's compilation process:
 * - When JavaCC parser produces ElementSimilarityJoin/ElementFuseJoin nodes,
 *   this generator compiles them into OpSimilarityJoin/OpFuseJoin operators.
 * - Supports both new relational semantics (binary join) and legacy filter semantics.
 * 
 * New relational semantics:
 * { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
 *   -> OpFuseJoin(leftOp, rightOp, leftVar, rightVar, tolerance, resultVar)
 * 
 * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
 *   -> OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, tolerance)
 * 
 * Legacy filter semantics (single pattern):
 * SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { pattern }
 *   -> OpSimilarityJoin(patternOp, patternOp, leftVar, rightVar, tolerance, legacyMode=true)
 */
public class AlgebraGeneratorProbabilistic extends AlgebraGenerator {
    
    // Store context and depth locally since parent's fields are private
    private final Context localContext;
    private final int localSubQueryDepth;
    
    /**
     * Default constructor.
     */
    public AlgebraGeneratorProbabilistic() {
        super();
        this.localContext = null;
        this.localSubQueryDepth = 0;
    }
    
    /**
     * Constructor with context.
     * @param context The execution context
     */
    public AlgebraGeneratorProbabilistic(Context context) {
        super(context);
        this.localContext = context;
        this.localSubQueryDepth = 0;
    }
    
    /**
     * Protected constructor with depth for subquery handling.
     * This ensures subqueries also use AlgebraGeneratorProbabilistic.
     * @param context The execution context
     * @param depth The subquery nesting depth
     */
    protected AlgebraGeneratorProbabilistic(Context context, int depth) {
        super(context, depth);
        this.localContext = context;
        this.localSubQueryDepth = depth;
    }
    
    /**
     * Compile a Query to algebra.
     * Uses this generator for all Element compilation, ensuring our custom
     * Elements are properly handled.
     */
    @Override
    public Op compile(Query query) {
        return super.compile(query);
    }
    
    /**
     * Compile an Element to algebra.
     * This is the main entry point for Element compilation.
     */
    @Override
    public Op compile(Element element) {
        return super.compile(element);
    }
    
    /**
     * Override compileElement to handle our custom Element types.
     * This method is called recursively during compilation.
     */
    @Override
    protected Op compileElement(Element elt) {
        if (elt == null) {
            return org.apache.jena.sparql.algebra.op.OpNull.create();
        }
        
        // Handle ElementSimilarityJoin
        if (elt instanceof ElementSimilarityJoin) {
            return compileSimilarityJoin((ElementSimilarityJoin) elt);
        }
        
        // Handle ElementFuseJoin
        if (elt instanceof ElementFuseJoin) {
            return compileFuseJoin((ElementFuseJoin) elt);
        }
        
        // Delegate to parent for standard elements
        return super.compileElement(elt);
    }
    
    /**
     * Override compileOneInGroup to handle our custom Elements within an ElementGroup.
     * This is called for each element in a group during group compilation.
     * 
     * For SIMILARITYJOIN and FUSEJOIN, they are binary operators that:
     * 1. Take the current accumulated result as the left operand (if no explicit left pattern)
     * 2. Compile their own left and right patterns if they have them
     */
    @Override
    protected Op compileOneInGroup(Element elt, Op current, Deque<Op> acc) {
        // Handle ElementSimilarityJoin in group context
        if (elt instanceof ElementSimilarityJoin) {
            ElementSimilarityJoin simJoin = (ElementSimilarityJoin) elt;
            return compileSimilarityJoinInGroup(simJoin, current);
        }
        
        // Handle ElementFuseJoin in group context
        if (elt instanceof ElementFuseJoin) {
            ElementFuseJoin fuseJoin = (ElementFuseJoin) elt;
            return compileFuseJoinInGroup(fuseJoin, current);
        }
        
        // Delegate to parent for standard elements
        return super.compileOneInGroup(elt, current, acc);
    }
    
    /**
     * Handle unknown elements - our custom Elements should be caught earlier,
     * but this provides a fallback.
     */
    @Override
    protected Op compileUnknownElement(Element element, String message) {
        // Handle ElementFuseJoin
        if (element instanceof ElementFuseJoin) {
            return compileFuseJoin((ElementFuseJoin) element);
        }
        
        // Handle ElementSimilarityJoin
        if (element instanceof ElementSimilarityJoin) {
            return compileSimilarityJoin((ElementSimilarityJoin) element);
        }
        
        // Delegate to parent for truly unknown elements
        return super.compileUnknownElement(element, message);
    }
    
    /**
     * Override compileElementSubquery to ensure subqueries also use AlgebraGeneratorProbabilistic.
     * This is critical because the parent class creates a new AlgebraGenerator instance,
     * which wouldn't recognize our custom Element types.
     */
    @Override
    protected Op compileElementSubquery(org.apache.jena.sparql.syntax.ElementSubQuery eltSubQuery) {
        // Use AlgebraGeneratorProbabilistic for subqueries to maintain custom Element support
        AlgebraGeneratorProbabilistic gen = new AlgebraGeneratorProbabilistic(localContext, localSubQueryDepth + 1);
        return gen.compile(eltSubQuery.getQuery());
    }
    
    // =========================================================================
    // SIMILARITYJOIN Compilation
    // =========================================================================
    
    /**
     * Compile ElementSimilarityJoin into OpSimilarityJoin.
     * 
     * @param simJoin The ElementSimilarityJoin syntax element
     * @return OpSimilarityJoin with compiled left and right operations
     */
    protected Op compileSimilarityJoin(ElementSimilarityJoin simJoin) {
        Element leftPattern = simJoin.getLeftPattern();
        Element rightPattern = simJoin.getRightPattern();
        
        Op leftOp;
        Op rightOp;
        boolean legacyMode;
        
        if (leftPattern != null) {
            // New relational semantics: explicit left and right patterns
            leftOp = compileElement(leftPattern);
            rightOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            legacyMode = false;
        } else {
            // Legacy filter semantics: single pattern contains both variables
            leftOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            rightOp = leftOp; // Same BGP contains both variables
            legacyMode = true;
        }
        
        // Extract parameters
        Var leftVar = Var.alloc(simJoin.getLeftVar());
        Var rightVar = Var.alloc(simJoin.getRightVar());
        double tolerance = simJoin.getTolerance();
        
        // Create OpSimilarityJoin
        return new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, tolerance, legacyMode);
    }
    
    /**
     * Compile ElementSimilarityJoin within a group context.
     * The current accumulated Op may be used as the left operand if no explicit left pattern.
     * 
     * @param simJoin The ElementSimilarityJoin syntax element
     * @param current The current accumulated Op from earlier elements in the group
     * @return OpSimilarityJoin integrated with the current accumulator
     */
    protected Op compileSimilarityJoinInGroup(ElementSimilarityJoin simJoin, Op current) {
        Element leftPattern = simJoin.getLeftPattern();
        Element rightPattern = simJoin.getRightPattern();
        
        Op leftOp;
        Op rightOp;
        boolean legacyMode;
        
        if (leftPattern != null) {
            // New relational semantics: explicit left and right patterns
            leftOp = compileElement(leftPattern);
            rightOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            legacyMode = false;
        } else {
            // Legacy filter semantics: single pattern contains both variables
            // The right pattern contains the BGP to filter
            leftOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            rightOp = leftOp; // Same BGP contains both variables
            legacyMode = true;
        }
        
        // Extract parameters
        Var leftVar = Var.alloc(simJoin.getLeftVar());
        Var rightVar = Var.alloc(simJoin.getRightVar());
        double tolerance = simJoin.getTolerance();
        
        // Create OpSimilarityJoin
        Op simJoinOp = new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, tolerance, legacyMode);
        
        // If there's a current accumulator, join it with the result
        if (current != null && !isUnit(current)) {
            return org.apache.jena.sparql.algebra.op.OpJoin.create(current, simJoinOp);
        }
        
        return simJoinOp;
    }
    
    // =========================================================================
    // FUSEJOIN Compilation
    // =========================================================================
    
    /**
     * Compile ElementFuseJoin into OpFuseJoin.
     * 
     * @param fuseJoin The ElementFuseJoin syntax element
     * @return OpFuseJoin with compiled left and right operations
     */
    protected Op compileFuseJoin(ElementFuseJoin fuseJoin) {
        Element leftPattern = fuseJoin.getLeftPattern();
        Element rightPattern = fuseJoin.getRightPattern();
        
        Op leftOp;
        Op rightOp;
        boolean legacyMode;
        
        if (leftPattern != null) {
            // New relational semantics: explicit left and right patterns
            leftOp = compileElement(leftPattern);
            rightOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            legacyMode = false;
        } else {
            // Legacy filter semantics: single pattern contains both variables
            leftOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            rightOp = leftOp; // Same BGP contains both variables
            legacyMode = true;
        }
        
        // Extract parameters
        Var leftVar = Var.alloc(fuseJoin.getLeftVar());
        Var rightVar = Var.alloc(fuseJoin.getRightVar());
        Var resultVar = Var.alloc(fuseJoin.getResultVar());
        double tolerance = fuseJoin.getTolerance();
        
        // Create OpFuseJoin
        return new OpFuseJoin(leftOp, rightOp, leftVar, rightVar, tolerance, resultVar, legacyMode);
    }
    
    /**
     * Compile ElementFuseJoin within a group context.
     * The current accumulated Op may be used as the left operand if no explicit left pattern.
     * 
     * @param fuseJoin The ElementFuseJoin syntax element
     * @param current The current accumulated Op from earlier elements in the group
     * @return OpFuseJoin integrated with the current accumulator
     */
    protected Op compileFuseJoinInGroup(ElementFuseJoin fuseJoin, Op current) {
        Element leftPattern = fuseJoin.getLeftPattern();
        Element rightPattern = fuseJoin.getRightPattern();
        
        Op leftOp;
        Op rightOp;
        boolean legacyMode;
        
        if (leftPattern != null) {
            // New relational semantics: explicit left and right patterns
            leftOp = compileElement(leftPattern);
            rightOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            legacyMode = false;
        } else {
            // Legacy filter semantics: single pattern contains both variables
            leftOp = (rightPattern != null) ? compileElement(rightPattern) : OpTable.unit();
            rightOp = leftOp;
            legacyMode = true;
        }
        
        // Extract parameters
        Var leftVar = Var.alloc(fuseJoin.getLeftVar());
        Var rightVar = Var.alloc(fuseJoin.getRightVar());
        Var resultVar = Var.alloc(fuseJoin.getResultVar());
        double tolerance = fuseJoin.getTolerance();
        
        // Create OpFuseJoin
        Op fuseJoinOp = new OpFuseJoin(leftOp, rightOp, leftVar, rightVar, tolerance, resultVar, legacyMode);
        
        // If there's a current accumulator, join it with the result
        if (current != null && !isUnit(current)) {
            return org.apache.jena.sparql.algebra.op.OpJoin.create(current, fuseJoinOp);
        }
        
        return fuseJoinOp;
    }
    
    // =========================================================================
    // Utility Methods
    // =========================================================================
    
    /**
     * Check if an Op is effectively empty (unit table).
     * A unit table is a join identity: (join unit(), X) = X
     */
    private static boolean isUnit(Op op) {
        if (op == null) {
            return true;
        }
        if (op instanceof OpTable) {
            return ((OpTable) op).isJoinIdentity();
        }
        return false;
    }
}
