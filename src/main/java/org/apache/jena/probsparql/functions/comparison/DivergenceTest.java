package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.sparql.expr.ExprEvalException;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase4;

/**
 * Boolean counterpart of DIVJOIN's threshold-aware comparison.
 * Returns whether the evaluator score is at most epsilon. The configured GMM
 * mode is preserved; alpha is used for both sequential tail probabilities.
 * Non-GMM paths use numerical PolyJSD and do not use alpha. Budget exhaustion
 * follows the evaluator's score fallback, so false is not proof of dissimilarity
 * and this API does not promise an alpha-level statistical test in every mode.
 */
public class DivergenceTest extends FunctionBase4 {
    public static final String URI = "http://probsparql.org/function#divergenceTest";

    @Override
    public NodeValue exec(NodeValue left, NodeValue right, NodeValue epsilon, NodeValue alpha) {
        if (!epsilon.isNumber() || !alpha.isNumber()) {
            throw new ExprEvalException("divergenceTest requires numeric epsilon and alpha");
        }
        double threshold = epsilon.getDouble();
        double tail = alpha.getDouble();
        if (!Double.isFinite(threshold) || threshold < 0) {
            throw new ExprEvalException("divergenceTest epsilon must be finite and non-negative");
        }
        if (!Double.isFinite(tail) || tail <= 0 || tail >= 0.5) {
            throw new ExprEvalException("divergenceTest alpha must be between 0 and 0.5 (exclusive)");
        }
        try {
            double score = ProbSPARQL.evaluateSimilarity(left.asNode(), right.asNode(), threshold, tail);
            if (!Double.isFinite(score)) throw new ExprEvalException("divergenceTest returned a non-finite score");
            return NodeValue.makeBoolean(score <= threshold);
        } catch (IllegalArgumentException ex) {
            throw new ExprEvalException("divergenceTest: " + ex.getMessage(), ex);
        }
    }
}
