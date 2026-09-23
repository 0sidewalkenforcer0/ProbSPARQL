package org.apache.jena.sparql.engine.iterator;

import org.apache.jena.atlas.io.IndentedWriter;
import org.apache.jena.graph.Node;
import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PrunedSimJoinEvaluator;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.sparql.algebra.Op;
import org.apache.jena.sparql.core.Var;
import org.apache.jena.sparql.engine.ExecutionContext;
import org.apache.jena.sparql.engine.QueryIterator;
import org.apache.jena.sparql.engine.binding.Binding;
import org.apache.jena.sparql.engine.binding.BindingBuilder;
import org.apache.jena.sparql.engine.main.QC;
import org.apache.jena.sparql.serializer.SerializationContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Nested-loop similarity join that applies the five-level pruning cascade
 * ({@link PrunedSimJoinEvaluator}) before falling back to full MC-sampled JSD.
 *
 * <p>Pruning statistics are accumulated into a {@link PruningStats} object that
 * can be retrieved via {@link #getPruningStats()} after iteration is complete.</p>
 *
 * <p>Semantics are identical to {@link QueryIterSimilarityJoin}: all pairs from
 * the cross product of the left and right tables that pass the JSD threshold are
 * yielded.  The only difference is the evaluation strategy for the JSD predicate.
 *
 * <p>The pruning cascade itself remains GMM-specific. Non-GMM distribution
 * pairs fall back to the polymorphic similarity evaluator without pruning.</p>
 */
public class QueryIterPrunedSimilarityJoin extends QueryIter {

    private static final org.slf4j.Logger LOG =
        org.slf4j.LoggerFactory.getLogger(QueryIterPrunedSimilarityJoin.class);

    /**
     * When true, only emit pairs where the right table index exceeds the left table
     * index, eliminating self-pairs and duplicate a→b / b→a orderings.
     * Enabled via {@code -Dprobsparql.simjoin.deduplicate=true}; default false.
     *
     * <p><strong>Precondition:</strong> this comparison is only meaningful when the left
     * and right patterns enumerate the <em>same</em> relation in the same order, i.e. a
     * self-join. For a join between two distinct relations — such as the CT-vs-SL
     * workload in Exp2 — index {@code i} on the left and index {@code j} on the right
     * denote unrelated rows, so the filter would discard legitimate pairs and understate
     * the result. Leave it disabled in that case.</p>
     */
    private static final boolean DEDUPLICATE =
        Boolean.getBoolean("probsparql.simjoin.deduplicate");

    private final Var leftVar;
    private final Var rightVar;
    private final double tailProbability;
    private final ExecutionContext execCxt;

    private final List<Binding> leftTable;
    private final List<Binding> rightTable;

    private int leftIndex  = 0;
    private int rightIndex = 0;
    private Binding nextBinding = null;

    private final PruningStats pruningStats;
    private final PrunedSimJoinEvaluator evaluator;

    public QueryIterPrunedSimilarityJoin(QueryIterator leftInput, Op rightOp,
                                         Var leftVar, Var rightVar, double tolerance,
                                         double tailProbability,
                                         ExecutionContext execCxt) {
        super(execCxt);

        if (tolerance < 0) {
            throw new IllegalArgumentException("Tolerance must be non-negative, got: " + tolerance);
        }

        this.leftVar  = leftVar;
        this.rightVar = rightVar;
        this.tailProbability = tailProbability;
        this.execCxt  = execCxt;

        this.pruningStats = new PruningStats();
        this.evaluator    = new PrunedSimJoinEvaluator(tolerance, tailProbability, pruningStats);

        this.leftTable  = materializeList(leftInput);
        QueryIterator rightInput = QC.execute(rightOp, QueryIterRoot.create(execCxt), execCxt);
        this.rightTable = materializeList(rightInput);
    }

    public PruningStats getPruningStats() {
        return pruningStats;
    }

    // ── QueryIter protocol ─────────────────────────────────────────────────

    @Override
    protected boolean hasNextBinding() {
        if (nextBinding != null) return true;
        nextBinding = findNextBinding();
        return nextBinding != null;
    }

    @Override
    protected Binding moveToNextBinding() {
        Binding b = nextBinding;
        nextBinding = null;
        return b;
    }

    @Override
    protected void closeIterator()  {
        if (!pruningStats.invariantHolds()) {
            // The reported pruning rate is only meaningful when every candidate pair is
            // accounted for at exactly one cascade level. Surface a breach loudly rather
            // than publishing a rate derived from inconsistent counters.
            LOG.warn("DIVJOIN pruning stats invariant violated: {}", pruningStats);
        }
        if (pruningStats.evaluationFailures > 0) {
            LOG.warn("DIVJOIN: {} candidate pair(s) could not be evaluated and were excluded"
                    + " from the result (of {} total).",
                pruningStats.evaluationFailures, pruningStats.totalPairs);
        }
        // Publish final stats so the Exp2 benchmark harness can retrieve them
        Exp2PruningHolder.set(pruningStats);
    }

    @Override
    protected void requestCancel() { }

    @Override
    public void output(IndentedWriter out, SerializationContext sCxt) {
        out.println("QueryIterPrunedSimilarityJoin");
        out.incIndent();
        out.println("Left Var:  " + leftVar);
        out.println("Right Var: " + rightVar);
        out.println("Tail Probability: " + tailProbability);
        out.println("Left  rows: " + leftTable.size());
        out.println("Right rows: " + rightTable.size());
        out.println(pruningStats.toString());
        out.decIndent();
    }

    // ── Nested-loop evaluation ─────────────────────────────────────────────

    private Binding findNextBinding() {
        while (leftIndex < leftTable.size()) {
            Binding lb = leftTable.get(leftIndex);

            while (rightIndex < rightTable.size()) {
                Binding rb = rightTable.get(rightIndex);
                rightIndex++;

                Binding merged = tryMerge(lb, rb);
                if (merged == null) continue;

                Node leftNode  = merged.get(leftVar);
                Node rightNode = merged.get(rightVar);
                if (leftNode == null || rightNode == null) continue;
                if (!ProbSPARQL.supportsSimilarityLiteral(leftNode)
                    || !ProbSPARQL.supportsSimilarityLiteral(rightNode)) continue;

                // Deduplication: only emit canonical (leftTableIdx < rightTableIdx) pairs,
                // matching n*(n-1)/2 self-join semantics. Valid only under the
                // same-relation precondition documented on DEDUPLICATE.
                if (DEDUPLICATE && rightIndex - 1 <= leftIndex) continue;

                boolean pass = evaluator.evaluate(leftNode, rightNode);
                if (pass) {
                    return merged;
                }
            }

            leftIndex++;
            rightIndex = 0;
        }
        return null;
    }

    private Binding tryMerge(Binding left, Binding right) {
        BindingBuilder builder = BindingBuilder.create();
        Iterator<Var> li = left.vars();
        while (li.hasNext()) {
            Var v = li.next();
            builder.add(v, left.get(v));
        }
        Iterator<Var> ri = right.vars();
        while (ri.hasNext()) {
            Var v = ri.next();
            Node rv = right.get(v);
            if (left.contains(v)) {
                if (!left.get(v).equals(rv)) return null;
            } else {
                builder.add(v, rv);
            }
        }
        return builder.build();
    }

    private static List<Binding> materializeList(QueryIterator iter) {
        List<Binding> list = new ArrayList<>();
        try {
            while (iter.hasNext()) list.add(iter.next());
        } finally {
            iter.close();
        }
        return list;
    }
}
