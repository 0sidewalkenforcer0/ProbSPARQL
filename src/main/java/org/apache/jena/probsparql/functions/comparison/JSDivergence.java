package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * Legacy SPARQL function {@code prob:jsdivergence} for GMM-only similarity
 * evaluation.
 *
 * <p>The public URI is preserved for backward compatibility. Internally this
 * function now delegates to {@link SimilarityEvaluator}, which reflects the
 * actual semantics more accurately: for V3/V4/V5 the returned numeric score is
 * a by-product of a threshold-oriented similarity decision pipeline, not
 * necessarily a uniformly precise JSD estimator.</p>
 *
 * <p>The decision threshold used by V3/V4/V5 in this legacy path comes from
 * {@code probsparql.sprt.epsilon}. Query operators such as
 * {@code SIMILARITYJOIN} should use the internal threshold-aware
 * {@code evaluateSimilarity(..., tolerance)} path instead so the query
 * tolerance is propagated into the evaluator.</p>
 */
public class JSDivergence extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#jsdivergence";

    private final SimilarityEvaluator legacyEvaluator;

    public JSDivergence() {
        this.legacyEvaluator = SimilarityEvaluator.legacy();
    }

    @Override
    public NodeValue exec(NodeValue gmm1Node, NodeValue gmm2Node) {
        GMMValue gmm1 = extractGMM(gmm1Node, "first");
        GMMValue gmm2 = extractGMM(gmm2Node, "second");
        return NodeValue.makeDouble(legacyEvaluator.evaluate(gmm1, gmm2));
    }

    private GMMValue extractGMM(NodeValue node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be a GMM literal");
        }

        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be of type " + GMMDatatype.URI);
        }

        return (GMMValue) value;
    }
}
