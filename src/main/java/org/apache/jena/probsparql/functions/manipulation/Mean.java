package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.DirichletDatatype;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase1;

/**
 * SPARQL function {@code prob:mean} — polymorphic expected-value computation.
 *
 * <p>Dispatches by RDF datatype:</p>
 * <ul>
 *   <li>{@code uq:gmmLiteral}       → weighted sum of component means</li>
 *   <li>{@code uq:histLiteral}      → weighted average of bin centres</li>
 *   <li>{@code uq:dirichletLiteral} → αᵢ / α₀, returned as JSON array</li>
 * </ul>
 *
 * <p>Returns a string of the form {@code "[v]"} for 1-D or {@code "[v1, v2, …]"}
 * for multi-D.</p>
 */
public class Mean extends FunctionBase1 {

    public static final String URI = "http://probsparql.org/function#mean";

    @Override
    public NodeValue exec(NodeValue distNode) {
        if (!distNode.isLiteral())
            throw new IllegalArgumentException("prob:mean: argument must be a distribution literal");

        String dtype = distNode.asNode().getLiteralDatatypeURI();

        if (GMMDatatype.URI.equals(dtype)) {
            GMMValue gmm = extractGMM(distNode);
            return NodeValue.makeString(formatVector(computeGMMMean(gmm)));
        }

        if (HistogramDatatype.URI.equals(dtype)) {
            HistogramValue hist = HistogramJSD.extractHistogram(distNode, "first");
            // Return scalar as single-element JSON array for consistency
            return NodeValue.makeString(String.format("[%.6f]", hist.mean()));
        }

        if (DirichletDatatype.URI.equals(dtype)) {
            DirichletValue dir = extractDirichlet(distNode);
            return NodeValue.makeString(formatVector(dir.mean()));
        }

        throw new IllegalArgumentException("prob:mean: unsupported distribution type: " + dtype);
    }

    private GMMValue extractGMM(NodeValue node) {
        Object v = node.asNode().getLiteralValue();
        if (!(v instanceof GMMValue))
            throw new IllegalArgumentException("Argument must be of type " + GMMDatatype.URI);
        return (GMMValue) v;
    }

    private DirichletValue extractDirichlet(NodeValue node) {
        Object v = node.asNode().getLiteralValue();
        if (!(v instanceof DirichletValue))
            throw new IllegalArgumentException("Argument must be of type " + DirichletDatatype.URI);
        return (DirichletValue) v;
    }

    private double[] computeGMMMean(GMMValue gmm) {
        int K = gmm.getNComponents(), d = gmm.getDimensions();
        double[] weights = gmm.getWeights();
        double[][] means  = gmm.getMeans();
        double[] mean = new double[d];
        for (int i = 0; i < K; i++)
            for (int j = 0; j < d; j++)
                mean[j] += weights[i] * means[i][j];
        return mean;
    }

    private String formatVector(double[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(String.format("%.6f", vector[i]));
        }
        sb.append("]");
        return sb.toString();
    }
}

/**
 * SPARQL function to compute the mean (expected value) of a GMM.
 * 
 * <p>For a GMM X ~ Σ w_i * N(μ_i, Σ_i), the expected value is:</p>
 * <pre>
 * E[X] = Σ w_i * μ_i
 * </pre>
 * 
 * <p>This is the weighted average of component means.</p>
 * 
 * <p>Returns a JSON string representing the mean vector:</p>
 * <ul>
 *   <li>1D: "[1.5]" or "1.5"</li>
 *   <li>Multi-D: "[1.5, 2.3, 0.8]"</li>
 * </ul>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?meanValue WHERE {
 *   ?rv uq:hasDistribution ?gmm .
 *   BIND(prob:mean(?gmm) AS ?meanValue)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
