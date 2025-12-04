package org.apache.jena.sparql.algebra.op;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.util.NodeIsomorphismMap;

import java.util.Objects;

/**
 * OpSimilarityJoin: Algebra operator for similarity join based on JS divergence.
 * 
 * Two modes are supported:
 * 
 * 1. New relational semantics (dual-input):
 *    { leftOp } SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { rightOp }
 * 
 * 2. Legacy filter semantics (single-input):
 *    SIMILARITYJOIN(?leftVar, ?rightVar, tolerance) { }
 *    All variables are in the same BGP; operates as a filter.
 * 
 * This is a binary operator with:
 * - leftOp: generates the left table
 * - rightOp: generates the right table (same as leftOp in legacy mode)
 * - Join condition: JS(?leftVar, ?rightVar) <= tolerance
 * - Unlike FuseJoin, this operator does NOT fuse distributions - just filters by similarity
 */
public class OpSimilarityJoin extends OpBase {
    private final Op leftOp;    // Left table operation
    private final Op rightOp;   // Right table operation
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final boolean legacyMode;  // True if this is legacy (filter) mode

    /**
     * Constructor for dual-input SimilarityJoin (new relational semantics).
     */
    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar, double tolerance) {
        this(leftOp, rightOp, leftVar, rightVar, tolerance, false);
    }
    
    /**
     * Constructor with explicit legacy mode flag.
     */
    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar, double tolerance, boolean legacyMode) {
        this.leftOp = leftOp;
        this.rightOp = rightOp;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.legacyMode = legacyMode;
    }

    // Getters for operation inputs
    public Op getLeftOp() {
        return leftOp;
    }

    public Op getRightOp() {
        return rightOp;
    }

    // Getters for join parameters
    public Var getLeftVar() {
        return leftVar;
    }

    public Var getRightVar() {
        return rightVar;
    }

    public double getTolerance() {
        return tolerance;
    }
    
    /**
     * Returns true if this is legacy (filter) mode.
     * In legacy mode, both variables are in the same BGP and the operation acts as a filter.
     */
    public boolean isLegacyMode() {
        return legacyMode;
    }

    @Override
    public String getName() {
        return "SimilarityJoin";
    }

    @Override
    public void visit(OpVisitor opVisitor) {
        // For standard visitors, visit the left sub-operation
        // OpExecutor will intercept this OpSimilarityJoin through executeOp() dispatch
        if (leftOp != null) {
            leftOp.visit(opVisitor);
        }
    }

    /**
     * Apply a transform to this operator.
     * OpSimilarityJoin is not a standard Op1 or Op2, so we don't transform sub-ops
     * through the standard Transform interface.
     */
    public Op apply(Transform transform) {
        return this;
    }

    @Override
    public int hashCode() {
        return Objects.hash(leftOp, rightOp, leftVar, rightVar, tolerance);
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpSimilarityJoin)) {
            return false;
        }
        OpSimilarityJoin otherOp = (OpSimilarityJoin) other;
        if (!this.leftVar.equals(otherOp.leftVar) ||
            !this.rightVar.equals(otherOp.rightVar) ||
            this.tolerance != otherOp.tolerance) {
            return false;
        }
        // Compare sub-operations
        if (leftOp == null && otherOp.leftOp != null) return false;
        if (leftOp != null && !leftOp.equalTo(otherOp.leftOp, labelMap)) return false;
        if (rightOp == null && otherOp.rightOp != null) return false;
        if (rightOp != null && !rightOp.equalTo(otherOp.rightOp, labelMap)) return false;
        return true;
    }

    @Override
    public void output(org.apache.jena.atlas.io.IndentedWriter out) {
        int line = out.getRow();
        out.println("(similarityjoin");
        out.incIndent();
        out.println("(leftVar=" + leftVar.getVarName() + " rightVar=" + rightVar.getVarName() + 
                    " tolerance=" + tolerance + ")");
        out.println("# Left Operation:");
        if (leftOp != null) {
            leftOp.output(out);
        } else {
            out.println("(empty)");
        }
        out.println();
        out.println("# Right Operation:");
        if (rightOp != null) {
            rightOp.output(out);
        } else {
            out.println("(empty)");
        }
        out.decIndent();
        if (line != out.getRow())
            out.ensureStartOfLine();
        out.print(")");
    }
    
    @Override
    public void output(org.apache.jena.atlas.io.IndentedWriter out, org.apache.jena.sparql.serializer.SerializationContext sCxt) {
        output(out);
    }
}
