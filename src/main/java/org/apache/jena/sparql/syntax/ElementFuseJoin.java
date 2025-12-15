package org.apache.jena.sparql.syntax;

import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * Syntax element representing FUSEJOIN pattern in SPARQL query.
 * 
 * New relational semantics syntax:
 * { leftPattern } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightPattern }
 * 
 * This represents a true binary join operation where:
 * - leftPattern generates the left table
 * - rightPattern generates the right table
 * - The join condition is based on JS divergence between leftVar and rightVar
 * - If JS <= tolerance, Bayesian fusion is performed and bound to resultVar
 */
public class ElementFuseJoin extends Element {
    private final Element leftPattern;   // Left table pattern
    private final Element rightPattern;  // Right table pattern
    private final String leftVar;
    private final String rightVar;
    private final double tolerance;
    private final String resultVar;

    /**
     * Constructor for new relational semantics with left and right patterns.
     */
    public ElementFuseJoin(Element leftPattern, Element rightPattern, 
                           String leftVar, String rightVar,
                           double tolerance, String resultVar) {
        this.leftPattern = leftPattern;
        this.rightPattern = rightPattern;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
    }
    
    /**
     * Legacy constructor for backward compatibility (single pattern mode).
     * In this mode, leftPattern is null and rightPattern contains the single pattern.
     */
    public ElementFuseJoin(Element pattern, String leftVar, String rightVar,
                           double tolerance, String resultVar) {
        this.leftPattern = null;
        this.rightPattern = pattern;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
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
        if (!(el2 instanceof ElementFuseJoin)) {
            return false;
        }
        ElementFuseJoin other = (ElementFuseJoin) el2;
        
        boolean leftEqual = (leftPattern == null && other.leftPattern == null) ||
                           (leftPattern != null && other.leftPattern != null && 
                            leftPattern.equalTo(other.leftPattern, isoMap));
        boolean rightEqual = (rightPattern == null && other.rightPattern == null) ||
                            (rightPattern != null && other.rightPattern != null && 
                             rightPattern.equalTo(other.rightPattern, isoMap));
        
        return leftVar.equals(other.leftVar) &&
               rightVar.equals(other.rightVar) &&
               Math.abs(tolerance - other.tolerance) < 1e-10 &&
               resultVar.equals(other.resultVar) &&
               leftEqual && rightEqual;
    }

    @Override
    public int hashCode() {
        int hash = "ElementFuseJoin".hashCode() ^
               leftVar.hashCode() ^
               rightVar.hashCode() ^
               Double.hashCode(tolerance) ^
               resultVar.hashCode();
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

    public String getResultVar() {
        return resultVar;
    }
}
