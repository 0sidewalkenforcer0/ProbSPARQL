package org.apache.jena.probsparql.functions.comparison;

import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function {@code prob:histjsd} — Jensen-Shannon divergence between two
 * histogram literals.
 *
 * <p>Computes the symmetric JSD using the discrete definition:</p>
 * <pre>
 * JSD(P ‖ Q) = ½ KL(P ‖ M) + ½ KL(Q ‖ M),   M = ½(P + Q)
 * where KL(P ‖ M) = Σᵢ pᵢ log(pᵢ / mᵢ)
 * </pre>
 *
 * <p>Convention: 0 log(0/m) = 0 (no contribution from empty bins).</p>
 *
 * <p>Both histograms must have the same number of bins and the same [min, max]
 * range; an {@link IllegalArgumentException} is thrown otherwise.</p>
 *
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * BIND(prob:histjsd(?h1, ?h2) AS ?jsd)
 * </pre>
 *
 * @see HistogramValue
 */
public class HistogramJSD extends FunctionBase2 {

    public static final String URI = "http://probsparql.org/function#histjsd";

    @Override
    public NodeValue exec(NodeValue arg1, NodeValue arg2) {
        HistogramValue h1 = extractHistogram(arg1, "first");
        HistogramValue h2 = extractHistogram(arg2, "second");

        if (!h1.isCompatible(h2))
            throw new IllegalArgumentException(
                    "Histograms must have the same B, min and max. "
                    + "Got B1=" + h1.getB() + " B2=" + h2.getB()
                    + " min1=" + h1.getMin() + " min2=" + h2.getMin()
                    + " max1=" + h1.getMax() + " max2=" + h2.getMax());

        double jsd = computeJSD(h1.probabilities(), h2.probabilities());
        return NodeValue.makeDouble(jsd);
    }

    // -----------------------------------------------------------------------
    // Core computation
    // -----------------------------------------------------------------------

    /**
     * Discrete Jensen-Shannon divergence.
     *
     * @param p probability mass array (length B, sums to 1)
     * @param q probability mass array (length B, sums to 1)
     * @return JSD ∈ [0, log(2)]
     */
    public static double computeJSD(double[] p, double[] q) {
        int B = p.length;
        double kl_pm = 0.0;
        double kl_qm = 0.0;

        for (int i = 0; i < B; i++) {
            double m = 0.5 * (p[i] + q[i]);
            if (p[i] > 0 && m > 0) kl_pm += p[i] * Math.log(p[i] / m);
            if (q[i] > 0 && m > 0) kl_qm += q[i] * Math.log(q[i] / m);
        }

        double jsd = 0.5 * kl_pm + 0.5 * kl_qm;
        // Clamp against floating-point noise
        return Math.max(0.0, Math.min(jsd, Math.log(2)));
    }

    // -----------------------------------------------------------------------
    // Extraction helper (shared with HistogramCDF / HistogramMean)
    // -----------------------------------------------------------------------

    public static HistogramValue extractHistogram(NodeValue node, String position) {
        if (!node.isLiteral())
            throw new IllegalArgumentException(
                    "The " + position + " argument must be a histogram literal");
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof HistogramValue))
            throw new IllegalArgumentException(
                    "The " + position + " argument must be of type "
                    + HistogramDatatype.URI + ", got: "
                    + node.asNode().getLiteralDatatypeURI());
        return (HistogramValue) value;
    }
}
