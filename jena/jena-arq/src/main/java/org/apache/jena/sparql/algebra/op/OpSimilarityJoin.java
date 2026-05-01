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
 * Algebra operator for SIMILARITYJOIN - similarity-based filtering using JS divergence.
 * 
 * ProbSPARQL Extension.
 * 
 * This operator performs filtering based on:
 * JS(leftVar, rightVar) <= tolerance
 * 
 * Unlike FUSEJOIN, no fusion is performed - just filtering based on similarity.
 */
public class OpSimilarityJoin extends Op1 {
    private final Op rightOp;        // Right sub-operation (for relational semantics)
    private final Var leftVar;       // Left distribution variable
    private final Var rightVar;      // Right distribution variable  
    private final double tolerance;  // JS divergence threshold
    private final double tailProbability; // One-sided tail probability for V3/V5
    private final boolean legacyMode; // True if using single-pattern (legacy) semantics

    /**
     * Constructor for legacy single-pattern mode.
     */
    public OpSimilarityJoin(Op subOp, Var leftVar, Var rightVar, 
                            double tolerance, double tailProbability, boolean legacyMode) {
        super(subOp);
        this.rightOp = null;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.tailProbability = tailProbability;
        this.legacyMode = legacyMode;
    }

    /**
     * Constructor for new relational semantics with left and right sub-operations.
     */
    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                            double tolerance, double tailProbability) {
        super(leftOp);
        this.rightOp = rightOp;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.tailProbability = tailProbability;
        this.legacyMode = false;
    }

    @Override
    public String getName() {
        return Tags.tagSimilarityJoin;
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
            return new OpSimilarityJoin(subOp, leftVar, rightVar, tolerance, tailProbability, true);
        } else {
            // In relational semantics, subOp is the leftOp, and we use the current rightOp
            return new OpSimilarityJoin(subOp, this.rightOp, leftVar, rightVar, tolerance, tailProbability);
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
        hash ^= Double.hashCode(tailProbability);
        if (getSubOp() != null) hash ^= getSubOp().hashCode();
        if (rightOp != null) hash ^= rightOp.hashCode();
        return hash;
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpSimilarityJoin)) return false;
        OpSimilarityJoin otherSim = (OpSimilarityJoin) other;
        
        if (!leftVar.equals(otherSim.leftVar)) return false;
        if (!rightVar.equals(otherSim.rightVar)) return false;
        if (Math.abs(tolerance - otherSim.tolerance) > 1e-10) return false;
        if (Math.abs(tailProbability - otherSim.tailProbability) > 1e-10) return false;
        if (legacyMode != otherSim.legacyMode) return false;
        
        if (getSubOp() == null && otherSim.getSubOp() != null) return false;
        if (getSubOp() != null && !getSubOp().equalTo(otherSim.getSubOp(), labelMap)) return false;
        
        if (rightOp == null && otherSim.rightOp != null) return false;
        if (rightOp != null && !rightOp.equalTo(otherSim.rightOp, labelMap)) return false;
        
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

    public double getTailProbability() {
        return tailProbability;
    }

    public boolean isLegacyMode() {
        return legacyMode;
    }
}
