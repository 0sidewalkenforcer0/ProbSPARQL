package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

import java.util.Locale;

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
        return NodeValue.makeString(formatVector(hist.meanVector()));
    }

    private String formatVector(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format(Locale.ROOT, "%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}
