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

package org.apache.jena.sparql.algebra.op;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.sse.Tags;
import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * Algebra operator for FUSEJOIN - Bayesian fusion join based on JS divergence.
 * 
 * ProbSPARQL Extension.
 * 
 * This operator performs:
 * 1. Similarity check: JS(leftVar, rightVar) <= tolerance
 * 2. If satisfied: Bayesian fusion of the two distributions -> resultVar
 */
public class OpFuseJoin extends Op1 {
    private final Op rightOp;        // Right sub-operation (for relational semantics)
    private final Var leftVar;       // Left distribution variable
    private final Var rightVar;      // Right distribution variable  
    private final double tolerance;  // JS divergence threshold
    private final Var resultVar;     // Variable to bind fusion result
    private final boolean legacyMode; // True if using single-pattern (legacy) semantics

    /**
     * Constructor for legacy single-pattern mode.
     */
    public OpFuseJoin(Op subOp, Var leftVar, Var rightVar, 
                      double tolerance, Var resultVar, boolean legacyMode) {
        super(subOp);
        this.rightOp = null;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
        this.legacyMode = legacyMode;
    }

    /**
     * Constructor for new relational semantics with left and right sub-operations.
     */
    public OpFuseJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                      double tolerance, Var resultVar) {
        super(leftOp);
        this.rightOp = rightOp;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
        this.legacyMode = false;
    }

    @Override
    public String getName() {
        return Tags.tagFuseJoin;
    }

    @Override
    public void visit(OpVisitor opVisitor) {
        // Standard visitors will traverse sub-operations
        if (getSubOp() != null) {
            getSubOp().visit(opVisitor);
        }
        if (rightOp != null) {
            rightOp.visit(opVisitor);
        }
    }

    @Override
    public Op1 copy(Op subOp) {
        if (legacyMode) {
            return new OpFuseJoin(subOp, leftVar, rightVar, tolerance, resultVar, true);
        } else {
            // In relational semantics, subOp is the leftOp, and we use the current rightOp
            return new OpFuseJoin(subOp, this.rightOp, leftVar, rightVar, tolerance, resultVar);
        }
    }

    @Override
    public Op apply(Transform transform, Op subOp) {
        return transform.transform(this, subOp);
    }

    @Override
    public int hashCode() {
        int hash = getName().hashCode();
        hash ^= leftVar.hashCode();
        hash ^= rightVar.hashCode();
        hash ^= Double.hashCode(tolerance);
        hash ^= resultVar.hashCode();
        if (getSubOp() != null) hash ^= getSubOp().hashCode();
        if (rightOp != null) hash ^= rightOp.hashCode();
        return hash;
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpFuseJoin)) return false;
        OpFuseJoin otherFuse = (OpFuseJoin) other;
        
        if (!leftVar.equals(otherFuse.leftVar)) return false;
        if (!rightVar.equals(otherFuse.rightVar)) return false;
        if (Math.abs(tolerance - otherFuse.tolerance) > 1e-10) return false;
        if (!resultVar.equals(otherFuse.resultVar)) return false;
        if (legacyMode != otherFuse.legacyMode) return false;
        
        if (getSubOp() == null && otherFuse.getSubOp() != null) return false;
        if (getSubOp() != null && !getSubOp().equalTo(otherFuse.getSubOp(), labelMap)) return false;
        
        if (rightOp == null && otherFuse.rightOp != null) return false;
        if (rightOp != null && !rightOp.equalTo(otherFuse.rightOp, labelMap)) return false;
        
        return true;
    }

    // Getters
    public Op getLeftOp() {
        return getSubOp();
    }

    public Op getRightOp() {
        return rightOp;
    }

    public Var getLeftVar() {
        return leftVar;
    }

    public Var getRightVar() {
        return rightVar;
    }

    public double getTolerance() {
        return tolerance;
    }

    public Var getResultVar() {
        return resultVar;
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }
}

