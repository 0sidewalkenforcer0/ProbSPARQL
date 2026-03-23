package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function {@code prob:histmean} — expected value of a histogram.
 *
 * <p>Computed as the weighted average over bin centres:</p>
 * <pre>E[X] = Σᵢ centre(i) · p(i)</pre>
 *
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * BIND(prob:histmean(?hist) AS ?meanValue)
 * </pre>
 *
 * @see HistogramValue#mean()
 */
public class HistogramMean extends FunctionBase1 {

    public static final String URI = "http://probsparql.org/function#histmean";

    @Override
    public NodeValue exec(NodeValue histNode) {
        HistogramValue hist = HistogramJSD.extractHistogram(histNode, "first");
        return NodeValue.makeDouble(hist.mean());
    }
}
