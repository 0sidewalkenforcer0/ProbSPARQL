package org.apache.jena.sparql.algebra.op;

import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.OpVisitor;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.util.NodeIsomorphismMap;

/**
 * SPARQL algebra operator for probabilistic fusion join.
 * 
 * Two modes are supported:
 * 
 * 1. New relational semantics (dual-input):
 *    { leftOp } FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { rightOp }
 * 
 * 2. Legacy filter semantics (single-input):
 *    FUSEJOIN(?leftVar, ?rightVar, tolerance, ?resultVar) { }
 *    All variables are in the same BGP; operates as a filter with fusion.
 * 
 * This is a binary operator with:
 * - leftOp: generates the left table
 * - rightOp: generates the right table (same as leftOp in legacy mode)
 * - Join condition: JS(?leftVar, ?rightVar) <= tolerance
 * - If satisfied: performs Bayesian fusion and binds to resultVar
 */
public class OpFuseJoin extends OpBase {
    private final Op leftOp;    // Left table operation
    private final Op rightOp;   // Right table operation
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final Var resultVar;
    private final boolean legacyMode;  // True if this is legacy (filter) mode

    /**
     * Constructor for dual-input FuseJoin (new relational semantics).
     */
    public OpFuseJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar, 
                      double tolerance, Var resultVar) {
        this(leftOp, rightOp, leftVar, rightVar, tolerance, resultVar, false);
    }
    
    /**
     * Constructor with explicit legacy mode flag.
     */
    public OpFuseJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar, 
                      double tolerance, Var resultVar, boolean legacyMode) {
        this.leftOp = leftOp;
        this.rightOp = rightOp;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.resultVar = resultVar;
        this.legacyMode = legacyMode;
    }

    @Override
    public String getName() {
        return "fusejoin";
    }

    /**
     * Apply a transform to this operator.
     * OpFuseJoin is not a standard Op1 or Op2, so we don't transform sub-ops
     * through the standard Transform interface.
     */
    public Op apply(Transform transform) {
        return this;
    }

    @Override
    public void visit(OpVisitor opVisitor) {
        // For standard visitors, we need to visit both sub-operations
        // OpExecutor will intercept this OpFuseJoin through executeOp() dispatch
        if (leftOp != null) {
            leftOp.visit(opVisitor);
        }
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpFuseJoin)) {
            return false;
        }
        OpFuseJoin opFuse = (OpFuseJoin) other;
        if (!leftVar.equals(opFuse.leftVar) ||
            !rightVar.equals(opFuse.rightVar) ||
            Math.abs(tolerance - opFuse.tolerance) > 1e-10 ||
            !resultVar.equals(opFuse.resultVar)) {
            return false;
        }
        // Compare sub-operations
        if (leftOp == null && opFuse.leftOp != null) return false;
        if (leftOp != null && !leftOp.equalTo(opFuse.leftOp, labelMap)) return false;
        if (rightOp == null && opFuse.rightOp != null) return false;
        if (rightOp != null && !rightOp.equalTo(opFuse.rightOp, labelMap)) return false;
        return true;
    }

    @Override
    public int hashCode() {
        int h = getName().hashCode();
        h ^= leftVar.hashCode();
        h ^= rightVar.hashCode();
        h ^= Double.hashCode(tolerance);
        h ^= resultVar.hashCode();
        if (leftOp != null) h ^= leftOp.hashCode();
        if (rightOp != null) h ^= rightOp.hashCode();
        return h;
    }

    // Getters for operation inputs
    public Op getLeftOp() {
        return leftOp;
    }

    public Op getRightOp() {
        return rightOp;
    }

    // Getters for join parameters (return Var directly)
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
    
    /**
     * Returns true if this is legacy (filter) mode.
     * In legacy mode, both variables are in the same BGP and the operation acts as a filter.
     */
    public boolean isLegacyMode() {
        return legacyMode;
    }

    // Convenience methods returning Var (for compatibility)
    public Var getLeftVarObj() {
        return leftVar;
    }

    public Var getRightVarObj() {
        return rightVar;
    }

    public Var getResultVarObj() {
        return resultVar;
    }
    
    @Override
    public void output(org.apache.jena.atlas.io.IndentedWriter out) {
        int line = out.getRow();
        out.println("(fusejoin");
        out.incIndent();
        out.println("(leftVar=" + leftVar.getVarName() + " rightVar=" + rightVar.getVarName() + 
                    " tolerance=" + tolerance + " resultVar=" + resultVar.getVarName() + ")");
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
