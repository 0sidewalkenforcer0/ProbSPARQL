package org.apache.jena.sparql.syntax;

import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * Syntax element representing SIMILARITYJOIN pattern in SPARQL query.
 * 
 * New relational semantics syntax:
 * { leftPattern } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightPattern }
 * 
 * This represents a true binary join operation where:
 * - leftPattern generates the left table
 * - rightPattern generates the right table
 * - The join condition is JS(?leftVar, ?rightVar) <= tolerance
 * - Unlike FUSEJOIN, no fusion is performed - just filtering based on similarity
 */
public class ElementSimilarityJoin extends Element {
    private final Element leftPattern;   // Left table pattern
    private final Element rightPattern;  // Right table pattern
    private final String leftVar;
    private final String rightVar;
    private final double tolerance;

    /**
     * Constructor for new relational semantics with left and right patterns.
     */
    public ElementSimilarityJoin(Element leftPattern, Element rightPattern, 
                                  String leftVar, String rightVar, double tolerance) {
        this.leftPattern = leftPattern;
        this.rightPattern = rightPattern;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
    }
    
    /**
     * Legacy constructor for backward compatibility (single pattern mode).
     * In this mode, leftPattern is null and rightPattern contains the single pattern.
     */
    public ElementSimilarityJoin(Element pattern, String leftVar, String rightVar, 
                                  double tolerance) {
        this.leftPattern = null;
        this.rightPattern = pattern;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
    }

    @Override
    public void visit(ElementVisitor v) {
        // Check if visitor implements our extended interface
        if (v instanceof ElementVisitorProbabilistic) {
            ((ElementVisitorProbabilistic) v).visit(this);
        } else {
            // Fallback: visit both patterns for standard visitors
            // This allows standard Jena visitors to still traverse the tree
            if (leftPattern != null) {
                leftPattern.visit(v);
            }
            if (rightPattern != null) {
                rightPattern.visit(v);
            }
        }
    }

    @Override
    public boolean equalTo(Element el2, NodeIsomorphismMap isoMap) {
        if (!(el2 instanceof ElementSimilarityJoin)) {
            return false;
        }
        ElementSimilarityJoin other = (ElementSimilarityJoin) el2;
        
        boolean leftEqual = (leftPattern == null && other.leftPattern == null) ||
                           (leftPattern != null && other.leftPattern != null && 
                            leftPattern.equalTo(other.leftPattern, isoMap));
        boolean rightEqual = (rightPattern == null && other.rightPattern == null) ||
                            (rightPattern != null && other.rightPattern != null && 
                             rightPattern.equalTo(other.rightPattern, isoMap));
        
        return leftVar.equals(other.leftVar) &&
               rightVar.equals(other.rightVar) &&
               Math.abs(tolerance - other.tolerance) < 1e-10 &&
               leftEqual && rightEqual;
    }

    @Override
    public int hashCode() {
        int hash = "ElementSimilarityJoin".hashCode() ^
               leftVar.hashCode() ^
               rightVar.hashCode() ^
               Double.hashCode(tolerance);
        if (leftPattern != null) {
            hash ^= leftPattern.hashCode();
        }
        if (rightPattern != null) {
            hash ^= rightPattern.hashCode();
        }
        return hash;
    }

    // Getters
    public Element getLeftPattern() {
        return leftPattern;
    }
    
    public Element getRightPattern() {
        return rightPattern;
    }
    
    /**
     * For backward compatibility - returns rightPattern as the single pattern.
     * @deprecated Use getLeftPattern() and getRightPattern() instead.
     */
    @Deprecated
    public Element getPattern() {
        return rightPattern;
    }
    
    /**
     * Check if this is using the new relational semantics (with left pattern).
     */
    public boolean hasLeftPattern() {
        return leftPattern != null;
    }

    public String getLeftVar() {
        return leftVar;
    }

    public String getRightVar() {
        return rightVar;
    }

    public double getTolerance() {
        return tolerance;
    }
}

