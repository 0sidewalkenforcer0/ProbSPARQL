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

import java.util.Locale;

/**
 * SPARQL function {@code prob:map} — polymorphic MAP (mode) estimate.
 *
 * <p>Dispatches by RDF datatype:</p>
 * <ul>
 *   <li>{@code uq:gmmLiteral}       → mean of component with maximum weight</li>
 *   <li>{@code uq:histLiteral}      → centre of the bin with the highest probability mass</li>
 *   <li>{@code uq:dirichletLiteral} → (αᵢ − 1) / (α₀ − k) if all αᵢ > 1</li>
 * </ul>
 */
public class Map extends FunctionBase1 {

    public static final String URI = "http://probsparql.org/function#map";

    @Override
    public NodeValue exec(NodeValue distNode) {
        if (!distNode.isLiteral())
            throw new IllegalArgumentException("prob:map: argument must be a distribution literal");

        String dtype = distNode.asNode().getLiteralDatatypeURI();

        if (GMMDatatype.URI.equals(dtype)) {
            GMMValue gmm = extractGMM(distNode);
            return NodeValue.makeString(formatVector(computeGMMMAP(gmm)));
        }

        if (HistogramDatatype.URI.equals(dtype)) {
            HistogramValue hist = HistogramJSD.extractHistogram(distNode, "first");
            return NodeValue.makeString(formatVector(hist.mapPoint()));
        }

        if (DirichletDatatype.URI.equals(dtype)) {
            DirichletValue dir = extractDirichlet(distNode);
            return NodeValue.makeString(formatVector(dir.map()));
        }

        throw new IllegalArgumentException("prob:map: unsupported distribution type: " + dtype);
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

    private double[] computeGMMMAP(GMMValue gmm) {
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        int maxIdx = 0;
        for (int i = 1; i < gmm.getNComponents(); i++)
            if (weights[i] > weights[maxIdx]) maxIdx = i;
        return means[maxIdx];
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
