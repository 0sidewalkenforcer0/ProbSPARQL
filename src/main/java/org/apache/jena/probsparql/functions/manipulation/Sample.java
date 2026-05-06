package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

import java.math.BigInteger;

/**
 * SPARQL function {@code prob:sample} — draw samples from a supported
 * probabilistic distribution literal.
 *
 * <p>The first argument is dispatched by the parsed RDF literal value. Any
 * datatype implementing {@link Sampleable} can be sampled. The result is a JSON
 * string with shape {@code [n][d]}, so 1-D distributions still return rows such
 * as {@code [[x1], [x2], ...]}.</p>
 */
public class Sample extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#sample";

    private static final int MAX_SAMPLES = Math.max(
        1,
        Integer.getInteger("probsparql.sample.max", 10_000)
    );

    @Override
    public NodeValue exec(NodeValue distNode, NodeValue countNode) {
        if (!distNode.isLiteral()) {
            throw new IllegalArgumentException("prob:sample: first argument must be a distribution literal");
        }

        Object value = distNode.asNode().getLiteralValue();
        if (!(value instanceof Sampleable sampleable)) {
            throw new IllegalArgumentException(
                "prob:sample: unsupported distribution type: "
                    + distNode.asNode().getLiteralDatatypeURI());
        }

        int count = extractSampleCount(countNode);
        return NodeValue.makeString(formatSamples(sampleable.sample(count)));
    }

    private int extractSampleCount(NodeValue node) {
        if (!node.isInteger()) {
            throw new IllegalArgumentException("prob:sample: second argument must be a positive integer");
        }

        BigInteger count = node.getInteger();
        if (count.signum() <= 0) {
            throw new IllegalArgumentException("prob:sample: sample count must be positive, got: " + count);
        }
        if (count.compareTo(BigInteger.valueOf(MAX_SAMPLES)) > 0) {
            throw new IllegalArgumentException(
                "prob:sample: sample count " + count + " exceeds maximum " + MAX_SAMPLES
                    + " (set -Dprobsparql.sample.max to override)");
        }
        return count.intValue();
    }

    private String formatSamples(double[][] samples) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < samples.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append("[");
            for (int j = 0; j < samples[i].length; j++) {
                if (j > 0) {
                    sb.append(",");
                }
                double value = samples[i][j];
                if (!Double.isFinite(value)) {
                    throw new IllegalStateException("prob:sample: sampled non-finite value: " + value);
                }
                sb.append(Double.toString(value));
            }
            sb.append("]");
        }
        sb.append("]");
        return sb.toString();
    }
}
