package org.apache.jena.sparql.algebra.op;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.algebra.Transform;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.serializer.SerializationContext;
import org.apache.jena.sparql.util.NodeIsomorphismMap;

import java.util.Objects;

/**
 * OpSimilarityJoin: Algebra operator for similarity join based on JS divergence.
 *
 * Extends OpExt so that the standard Jena transformer/visitor framework treats it
 * as an opaque leaf (via OpExt.visit → opVisitor.visit(this) → visitExt) and
 * preserves it intact through TransformSimplify and other algebra transforms.
 *
 * Two modes:
 * 1. Relational (legacyMode=false): { leftOp } SIMILARITYJOIN(...) { rightOp }
 * 2. Legacy     (legacyMode=true ): SIMILARITYJOIN(...) { rightOp }  – filter semantics
 */
public class OpSimilarityJoin extends OpExt {
    private final Op leftOp;
    private final Op rightOp;
    private final Var leftVar;
    private final Var rightVar;
    private final double tolerance;
    private final double tailProbability;
    private final boolean legacyMode;

    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                            double tolerance, double tailProbability) {
        this(leftOp, rightOp, leftVar, rightVar, tolerance, tailProbability, false);
    }

    public OpSimilarityJoin(Op leftOp, Op rightOp, Var leftVar, Var rightVar,
                            double tolerance, double tailProbability, boolean legacyMode) {
        super("SimilarityJoin");
        this.leftOp = leftOp;
        this.rightOp = rightOp;
        this.leftVar = leftVar;
        this.rightVar = rightVar;
        this.tolerance = tolerance;
        this.tailProbability = tailProbability;
        this.legacyMode = legacyMode;
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public Op getLeftOp()         { return leftOp; }
    public Op getRightOp()        { return rightOp; }
    public Var getLeftVar()       { return leftVar; }
    public Var getRightVar()      { return rightVar; }
    public double getTolerance()  { return tolerance; }
    public double getTailProbability() { return tailProbability; }
    public boolean isLegacyMode() { return legacyMode; }

    // ── OpExt abstract methods ────────────────────────────────────────────

    /**
     * Return an equivalent SPARQL expression for planning purposes.
     * Actual execution is handled by OpExecutorProbabilistic.
     */
    @Override
    public Op effectiveOp() {
        return leftOp != null ? leftOp : rightOp;
    }

    /**
     * Direct evaluation is not supported; execution goes through OpExecutorProbabilistic.
     */
    @Override
    public QueryIterator eval(QueryIterator input, ExecutionContext execCxt) {
        throw new UnsupportedOperationException(
            "OpSimilarityJoin.eval() must not be called directly; use QueryEngineProbabilistic");
    }

    @Override
    public void outputArgs(IndentedWriter out, SerializationContext sCxt) {
        out.print("(leftVar=" + leftVar.getVarName()
                + " rightVar=" + rightVar.getVarName()
                + " tolerance=" + tolerance
                + " tailProbability=" + tailProbability
                + " legacy=" + legacyMode + ")");
    }

    // ── Transform ─────────────────────────────────────────────────────────

    /** Preserve OpSimilarityJoin through all algebra transformations. */
    @Override
    public Op apply(Transform transform) {
        return this;
    }

    // ── Equality / hash ───────────────────────────────────────────────────

    @Override
    public int hashCode() {
        return Objects.hash(leftOp, rightOp, leftVar, rightVar, tolerance, tailProbability, legacyMode);
    }

    @Override
    public boolean equalTo(Op other, NodeIsomorphismMap labelMap) {
        if (!(other instanceof OpSimilarityJoin)) return false;
        OpSimilarityJoin o = (OpSimilarityJoin) other;
        if (!leftVar.equals(o.leftVar) || !rightVar.equals(o.rightVar)
                || this.tolerance != o.tolerance
                || this.tailProbability != o.tailProbability
                || this.legacyMode != o.legacyMode)
            return false;
        boolean leftEq  = (leftOp  == null) == (o.leftOp  == null)
                && (leftOp  == null || leftOp.equalTo(o.leftOp, labelMap));
        boolean rightEq = (rightOp == null) == (o.rightOp == null)
                && (rightOp == null || rightOp.equalTo(o.rightOp, labelMap));
        return leftEq && rightEq;
    }
}
