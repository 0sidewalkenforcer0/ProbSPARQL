package org.apache.jena.sparql.engine.iterator;

import org.apache.jena.graph.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Failure accounting for the similarity-join iterators.
 *
 * <p>A nested-loop similarity join evaluates a divergence per candidate pair, and any
 * one of those evaluations can throw — mismatched dimensionality, a singular
 * covariance, an out-of-range parameter. Aborting the query on the first such pair is
 * too brittle for a join over thousands of rows, but silently skipping is worse: the
 * pair simply never appears in the result, so a data error is indistinguishable from a
 * genuine non-match, and a fault that affects <em>every</em> pair produces a
 * confidently empty answer.</p>
 *
 * <p>The middle ground implemented here is to drop the pair, count it, and log the
 * first few occurrences with enough context to identify the cause. Callers surface the
 * total so it is visible after the fact. Setting
 * {@code -Dprobsparql.simjoin.failOnError=true} switches to fail-fast instead, which
 * is the appropriate setting when validating a dataset or a benchmark run.</p>
 */
final class SimilarityJoinFailures {

    private static final Logger logger = LoggerFactory.getLogger(SimilarityJoinFailures.class);

    /** Rethrow instead of skipping. Useful for dataset and benchmark validation. */
    private static final boolean FAIL_ON_ERROR =
        Boolean.getBoolean("probsparql.simjoin.failOnError");

    /** Log at most this many failures per join, to bound log volume on a bad dataset. */
    private static final long MAX_LOGGED = 5;

    private SimilarityJoinFailures() {
    }

    /**
     * Record one failed pair evaluation.
     *
     * @param cause     the exception thrown by the evaluator
     * @param leftNode  left operand of the failed pair
     * @param rightNode right operand of the failed pair
     * @param counter   per-join failure counter; incremented in place
     * @throws RuntimeException the original cause, if fail-fast is enabled
     */
    static void record(RuntimeException cause, Node leftNode, Node rightNode, long[] counter) {
        if (FAIL_ON_ERROR) {
            throw cause;
        }
        counter[0]++;
        if (counter[0] <= MAX_LOGGED) {
            logger.warn("Similarity evaluation failed for a candidate pair; the pair is excluded"
                    + " from the result. left={} right={}: {}",
                describe(leftNode), describe(rightNode), cause.toString());
            if (counter[0] == MAX_LOGGED) {
                logger.warn("Further similarity evaluation failures in this join will not be"
                    + " logged individually; the total is reported when the join completes.");
            }
        }
    }

    /**
     * Report the total once a join has finished, so a partially-failed join cannot pass
     * unnoticed.
     */
    static void reportTotal(long failures, String operatorName) {
        if (failures > 0) {
            logger.warn("{}: {} candidate pair(s) could not be evaluated and were excluded"
                    + " from the result. Re-run with -Dprobsparql.simjoin.failOnError=true"
                    + " to surface the underlying error.",
                operatorName, failures);
        }
    }

    /**
     * Short, bounded description of an operand for log messages.
     */
    private static String describe(Node node) {
        if (node == null) {
            return "null";
        }
        if (!node.isLiteral()) {
            return node.toString();
        }
        String lexical = node.getLiteralLexicalForm();
        String shortened = lexical.length() > 120 ? lexical.substring(0, 120) + "..." : lexical;
        return "\"" + shortened + "\"^^<" + node.getLiteralDatatypeURI() + ">";
    }
}
