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

        if (!pointNode.isNumber())
            throw new IllegalArgumentException(
                    "The second argument of prob:histcdf must be a numeric threshold");

        double x = pointNode.getDouble();
        return NodeValue.makeDouble(hist.cdf(x));
    }
}
