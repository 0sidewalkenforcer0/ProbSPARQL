/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jena.sparql.syntax;

import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * Syntax element representing FUSEJOIN pattern in SPARQL query.
 * 
 * ProbSPARQL Extension for Bayesian fusion join based on JS divergence.
 * 
 * Syntax: FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { pattern }
 * 
 * This represents a join operation where:
 * - pattern contains both leftVar and rightVar bindings
 * - The join condition is: JS(?leftVar, ?rightVar) <= tolerance
 * - If satisfied, Bayesian fusion is performed and bound to resultVar
 */
public class ElementFuseJoin extends Element {
    private final Element leftPattern;   // Left table pattern (null for legacy syntax)
    private final Element rightPattern;  // Right table pattern (or single pattern for legacy)
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
        // Visit both patterns for standard visitors
        // This allows standard Jena visitors to traverse the tree
        if (leftPattern != null) {
            leftPattern.visit(v);
        }
        if (rightPattern != null) {
            rightPattern.visit(v);
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

