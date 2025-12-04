package org.apache.jena.sparql.algebra;

import org.apache.jena.query.Query;
import org.apache.jena.sparql.algebra.op.OpFuseJoin;
import org.apache.jena.sparql.algebra.op.OpSimilarityJoin;
import org.apache.jena.sparql.algebra.op.OpTable;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.syntax.*;

/**
 * Algebra generator extension for probabilistic syntax elements.
 * Extends Jena's AlgebraGenerator to handle ElementFuseJoin and ElementSimilarityJoin.
 * 
 * New relational semantics:
 * { leftPattern } FUSEJOIN(...) { rightPattern } -> OpFuseJoin(leftOp, rightOp, ...)
 * { leftPattern } SIMILARITYJOIN(...) { rightPattern } -> OpSimilarityJoin(leftOp, rightOp, ...)
 * 
 * This generator compiles the left and right patterns separately and creates
 * the appropriate dual-input algebra operators.
 */
public class AlgebraGeneratorProbabilistic extends AlgebraGenerator {
    
    // Store metadata for later use by QueryEngineProbabilistic
    private ElementFuseJoin fuseJoinElement = null;
    private ElementSimilarityJoin similarityJoinElement = null;
    
    @Override
    public Op compile(Query query) {
        Op op = super.compile(query);
        return op;
    }
    
    @Override
    public Op compile(Element element) {
        // Search for FuseJoin and SimilarityJoin elements
        fuseJoinElement = findFuseJoin(element);
        similarityJoinElement = findSimilarityJoin(element);
        
        // Compile normally - custom elements will be handled in compileUnknownElement
        Op op = super.compile(element);
        return op;
    }
    
    /**
     * Get the stored ElementFuseJoin metadata.
     */
    public ElementFuseJoin getFuseJoinElement() {
        return fuseJoinElement;
    }
    
    /**
     * Get the stored ElementSimilarityJoin metadata.
     */
    public ElementSimilarityJoin getSimilarityJoinElement() {
        return similarityJoinElement;
    }
    
    /**
     * Find ElementFuseJoin in an Element tree
     */
    private ElementFuseJoin findFuseJoin(Element element) {
        if (element instanceof ElementFuseJoin) {
            return (ElementFuseJoin) element;
        }
        if (element instanceof ElementGroup) {
            ElementGroup group = (ElementGroup) element;
            for (Element elem : group.getElements()) {
                ElementFuseJoin found = findFuseJoin(elem);
                if (found != null) return found;
            }
        }
        return null;
    }
    
    /**
     * Find ElementSimilarityJoin in an Element tree
     */
    private ElementSimilarityJoin findSimilarityJoin(Element element) {
        if (element instanceof ElementSimilarityJoin) {
            return (ElementSimilarityJoin) element;
        }
        if (element instanceof ElementGroup) {
            ElementGroup group = (ElementGroup) element;
            for (Element elem : group.getElements()) {
                ElementSimilarityJoin found = findSimilarityJoin(elem);
                if (found != null) return found;
            }
        }
        return null;
    }
    
    @Override
    protected Op compileUnknownElement(Element element, String message) {
        // Handle ElementFuseJoin - compile left and right patterns separately
        if (element instanceof ElementFuseJoin) {
            ElementFuseJoin fuseJoin = (ElementFuseJoin) element;
            return compileFuseJoin(fuseJoin);
        }
        
        // Handle ElementSimilarityJoin - compile left and right patterns separately
        if (element instanceof ElementSimilarityJoin) {
            ElementSimilarityJoin simJoin = (ElementSimilarityJoin) element;
            return compileSimilarityJoin(simJoin);
        }
        
        // Delegate to parent for truly unknown elements
        return super.compileUnknownElement(element, message);
    }
    
    /**
     * Compile ElementFuseJoin into OpFuseJoin with two input operations.
     * 
     * New relational semantics:
     * { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
     * 
     * @param fuseJoin The ElementFuseJoin syntax element
     * @return OpFuseJoin with compiled left and right operations
     */
    private Op compileFuseJoin(ElementFuseJoin fuseJoin) {
        // Compile left pattern
        Element leftPattern = fuseJoin.getLeftPattern();
        Op leftOp = (leftPattern != null) ? compileElementPattern(leftPattern) : OpTable.unit();
        
        // Compile right pattern
        Element rightPattern = fuseJoin.getRightPattern();
        Op rightOp = (rightPattern != null) ? compileElementPattern(rightPattern) : OpTable.unit();
        
        // Create OpFuseJoin with both operations
        Var leftVar = Var.alloc(fuseJoin.getLeftVar());
        Var rightVar = Var.alloc(fuseJoin.getRightVar());
        Var resultVar = Var.alloc(fuseJoin.getResultVar());
        double tolerance = fuseJoin.getTolerance();
        
        return new OpFuseJoin(leftOp, rightOp, leftVar, rightVar, tolerance, resultVar);
    }
    
    /**
     * Compile ElementSimilarityJoin into OpSimilarityJoin with two input operations.
     * 
     * New relational semantics:
     * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
     * 
     * @param simJoin The ElementSimilarityJoin syntax element
     * @return OpSimilarityJoin with compiled left and right operations
     */
    private Op compileSimilarityJoin(ElementSimilarityJoin simJoin) {
        // Compile left pattern
        Element leftPattern = simJoin.getLeftPattern();
        Op leftOp = (leftPattern != null) ? compileElementPattern(leftPattern) : OpTable.unit();
        
        // Compile right pattern
        Element rightPattern = simJoin.getRightPattern();
        Op rightOp = (rightPattern != null) ? compileElementPattern(rightPattern) : OpTable.unit();
        
        // Create OpSimilarityJoin with both operations
        Var leftVar = Var.alloc(simJoin.getLeftVar());
        Var rightVar = Var.alloc(simJoin.getRightVar());
        double tolerance = simJoin.getTolerance();
        
        return new OpSimilarityJoin(leftOp, rightOp, leftVar, rightVar, tolerance);
    }
    
    /**
     * Helper method to compile an Element using this generator.
     */
    protected Op compileElementPattern(Element element) {
        // Use AlgebraGenerator's compile method
        return super.compile(element);
    }
}
