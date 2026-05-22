package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.datatypes.Sampleable;
import org.apache.jena.sparql.expr.NodeValue;

/**
 * Shared helpers for polymorphic distribution functions.
 */
public final class DistributionSupport {

    private DistributionSupport() {
    }

    public static Sampleable extractSampleable(NodeValue node, String functionName) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(functionName + ": first argument must be a distribution literal");
        }
        Object value = node.asNode().getLiteralValue();
        if (value instanceof Sampleable sampleable) {
            return sampleable;
        }
        throw new IllegalArgumentException(
            functionName + ": unsupported distribution type: " + node.asNode().getLiteralDatatypeURI());
    }

    public static int dimensions(Sampleable value) {
        if (value instanceof GMMValue gmm) {
            return gmm.getDimensions();
        }
        if (value instanceof HistogramValue histogram) {
            return histogram.getDimensions();
        }
        if (value instanceof DirichletValue dirichlet) {
            return dirichlet.getDimensions();
        }
        throw new IllegalArgumentException("Unsupported Sampleable implementation: " + value.getClass().getName());
    }

    public static double[] extractPoint(NodeValue node, int dimensions, String functionName) {
        if (dimensions == 1) {
            if (node.isNumber()) {
                return new double[]{node.getDouble()};
            }
            if (node.isString()) {
                return parseVector(node.getString(), dimensions, functionName);
            }
            throw new IllegalArgumentException(
                functionName + ": point must be numeric or a JSON array for 1-D distributions");
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(
                functionName + ": point must be a JSON array for " + dimensions + "D distributions");
        }
        return parseVector(node.getString(), dimensions, functionName);
    }

    public static double[] parseVector(String text, int dimensions, String functionName) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException(functionName + ": point must be a JSON array");
        }
        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException(functionName + ": empty point vector");
        }
        String[] parts = content.split(",");
        if (parts.length != dimensions) {
            throw new IllegalArgumentException(
                functionName + ": point dimension mismatch, expected " + dimensions + " got " + parts.length);
        }
        double[] point = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            point[i] = Double.parseDouble(parts[i].trim());
        }
        return point;
    }
}
