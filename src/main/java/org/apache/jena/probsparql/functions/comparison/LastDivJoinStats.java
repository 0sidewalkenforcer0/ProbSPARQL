package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * Experimental SPARQL function exposing the latest server-side DIVJOIN pruning
 * statistics for benchmark harnesses.
 *
 * <p>The function reads the latest {@link PruningStats} published by
 * {@code QueryIterPrunedSimilarityJoin} in the Fuseki JVM. It is intended for
 * sequential benchmark runs; concurrent DIVJOIN queries can overwrite the
 * global latest-stats slot.</p>
 */
public class LastDivJoinStats extends FunctionBase1 {

    public static final String URI = "http://probsparql.org/function#lastDivJoinStats";

    @Override
    public NodeValue exec(NodeValue fieldNode) {
        if (!fieldNode.isString()) {
            throw new IllegalArgumentException("prob:lastDivJoinStats: field name must be a string literal");
        }
        PruningStats stats = Exp2PruningHolder.getLast();
        if (stats == null) {
            throw new IllegalStateException(
                "prob:lastDivJoinStats: no DIVJOIN pruning stats have been published on this server");
        }

        return switch (fieldNode.getString()) {
            case "totalPairs" -> NodeValue.makeInteger(stats.totalPairs);
            case "prunedDim" -> NodeValue.makeInteger(stats.prunedByDim);
            case "prunedMean" -> NodeValue.makeInteger(stats.prunedByMean);
            case "prunedVar", "prunedVariance" -> NodeValue.makeInteger(stats.prunedByVariance);
            case "prunedBounds" -> NodeValue.makeInteger(stats.prunedByBounds);
            case "fullJSD", "computedFullJSD" -> NodeValue.makeInteger(stats.computedFullJSD);
            case "resultCount" -> NodeValue.makeInteger(stats.resultCount);
            case "pruningRate" -> NodeValue.makeDouble(stats.pruningRate());
            default -> throw new IllegalArgumentException(
                "prob:lastDivJoinStats: unknown field: " + fieldNode.getString());
        };
    }
}
