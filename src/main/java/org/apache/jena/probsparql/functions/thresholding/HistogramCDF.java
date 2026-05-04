package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:histcdf} — CDF of a histogram distribution.
 *
 * <p>Returns the cumulative probability P(X ≤ x) using linear interpolation
 * within bins.</p>
 *
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * BIND(prob:histcdf(?hist, 9.8) AS ?prob)
 * FILTER(?prob &gt;= 0.9)
 * </pre>
 *
 * @see HistogramValue#cdf(double)
 */
public class HistogramCDF extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#histcdf";

    @Override
    public NodeValue exec(NodeValue histNode, NodeValue pointNode) {
        HistogramValue hist = HistogramJSD.extractHistogram(histNode, "first");
        double[] point = extractPoint(pointNode, hist.getDimensions());
        return NodeValue.makeDouble(hist.cdf(point));
    }

    private double[] extractPoint(NodeValue node, int dimensions) {
        if (dimensions == 1) {
            if (node.isNumber()) {
                return new double[]{node.getDouble()};
            }
            if (node.isString()) {
                return parseVector(node.getString(), dimensions);
            }
            throw new IllegalArgumentException(
                "The second argument of prob:histcdf must be numeric or a JSON array for 1-D histograms");
        }
        if (!node.isString()) {
            throw new IllegalArgumentException(
                "The second argument of prob:histcdf must be a JSON array for " + dimensions + "D histograms");
        }
        return parseVector(node.getString(), dimensions);
    }

    private double[] parseVector(String str, int dimensions) {
        str = str.trim();
        if (!str.startsWith("[") || !str.endsWith("]")) {
            throw new IllegalArgumentException("Vector must be JSON array format: [v1, v2, ...]");
        }
        String content = str.substring(1, str.length() - 1).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Empty vector not allowed");
        }
        String[] parts = content.split(",");
        if (parts.length != dimensions) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + dimensions + ", got " + parts.length);
        }
        double[] vector = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = Double.parseDouble(parts[i].trim());
        }
        return vector;
    }
}
