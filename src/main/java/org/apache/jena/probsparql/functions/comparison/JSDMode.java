package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase3;

import java.util.Set;

/**
 * Experimental benchmark function {@code prob:jsdMode(?d1, ?d2, "V3_SPRT")}.
 *
 * <p>This is a SPARQL-visible dispatcher for the existing V1-V5 GMM evaluator
 * stack. It avoids changing global JVM configuration per request, which would
 * be unsafe under concurrent Fuseki queries.</p>
 */
public class JSDMode extends FunctionBase3 {

    public static final String URI = "http://probsparql.org/function#jsdMode";

    private static final Set<String> MODES = Set.of(
        JSDivergenceConfig.MODE_V1_MC,
        JSDivergenceConfig.MODE_V2_STRATIFIED,
        JSDivergenceConfig.MODE_V3_SPRT,
        JSDivergenceConfig.MODE_V4_BOUNDS,
        JSDivergenceConfig.MODE_V5_ADAPTIVE,
        JSDivergenceConfig.MODE_GT_100,
        JSDivergenceConfig.MODE_GT_1K,
        JSDivergenceConfig.MODE_GT_5K,
        JSDivergenceConfig.MODE_GT_10K
    );

    @Override
    public NodeValue exec(NodeValue left, NodeValue right, NodeValue modeNode) {
        GMMValue g1 = extractGMM(left, "first");
        GMMValue g2 = extractGMM(right, "second");
        if (!modeNode.isString()) {
            throw new IllegalArgumentException("prob:jsdMode: third argument must be a mode string");
        }

        String mode = modeNode.getString().trim();
        if (!MODES.contains(mode)) {
            throw new IllegalArgumentException("prob:jsdMode: unknown mode: " + mode);
        }

        SimilarityEvaluator evaluator = new SimilarityEvaluator(
            mode,
            JSDivergenceConfig.SPRT_EPSILON,
            JSDivergenceConfig.SPRT_ALPHA,
            JSDivergenceConfig.SPRT_BETA);
        return NodeValue.makeDouble(evaluator.evaluate(g1, g2));
    }

    private GMMValue extractGMM(NodeValue node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException("prob:jsdMode: " + position + " argument must be a GMM literal");
        }
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue gmm)) {
            throw new IllegalArgumentException("prob:jsdMode: " + position + " argument must be a gmmLiteral");
        }
        return gmm;
    }
}
