package org.apache.jena.sparql.syntax;

/**
 * Extended ElementVisitor interface for ProbSPARQL extensions.
 * 
 * This interface extends Jena's standard ElementVisitor to add support for
 * visiting ElementSimilarityJoin and ElementFuseJoin nodes in the syntax tree.
 * 
 * Usage:
 * - Implement this interface instead of ElementVisitor when you need to handle
 *   ProbSPARQL-specific Element types.
 * - The base ElementVisitor methods are inherited and should be implemented as usual.
 * 
 * Example:
 * <pre>
 * public class MyVisitor extends ElementVisitorBase implements ElementVisitorProbabilistic {
 *     @Override
 *     public void visit(ElementSimilarityJoin el) {
 *         // Handle SIMILARITYJOIN
 *     }
 *     
 *     @Override
 *     public void visit(ElementFuseJoin el) {
 *         // Handle FUSEJOIN
 *     }
 * }
 * </pre>
 */
public interface ElementVisitorProbabilistic extends ElementVisitor {
    
    /**
     * Visit an ElementSimilarityJoin node.
     * 
     * @param el The ElementSimilarityJoin to visit
     */
    void visit(ElementSimilarityJoin el);
    
    /**
     * Visit an ElementFuseJoin node.
     * 
     * @param el The ElementFuseJoin to visit
     */
    void visit(ElementFuseJoin el);
}

