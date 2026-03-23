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
 * SPARQL function {@code prob:std} — polymorphic standard-deviation computation.
 *
 * <p>Dispatches by RDF datatype:</p>
 * <ul>
 *   <li>{@code uq:gmmLiteral}       → sqrt(Var[X]) via law of total variance</li>
 *   <li>{@code uq:histLiteral}      → sqrt(Σ p_i (c_i - E[X])²)</li>
 *   <li>{@code uq:dirichletLiteral} → per-dimension Dirichlet variance formula</li>
 * </ul>
 */
public class Std extends FunctionBase1 {

    public static final String URI = "http://probsparql.org/function#std";

    @Override
    public NodeValue exec(NodeValue distNode) {
        if (!distNode.isLiteral())
            throw new IllegalArgumentException("prob:std: argument must be a distribution literal");

        String dtype = distNode.asNode().getLiteralDatatypeURI();

        if (GMMDatatype.URI.equals(dtype)) {
            GMMValue gmm = extractGMM(distNode);
            double[] var = computeGMMVariance(gmm);
            double[] std = new double[var.length];
            for (int i = 0; i < var.length; i++) std[i] = Math.sqrt(var[i]);
            return NodeValue.makeString(formatVector(std));
        }

        if (HistogramDatatype.URI.equals(dtype)) {
            HistogramValue hist = HistogramJSD.extractHistogram(distNode, "first");
            double mu  = hist.mean();
            double[] probs   = hist.probabilities();
            double[] centers = hist.binCenters();
            double var = 0.0;
            for (int i = 0; i < hist.getB(); i++) {
                double d = centers[i] - mu;
                var += probs[i] * d * d;
            }
            return NodeValue.makeString(String.format("[%.6f]", Math.sqrt(var)));
        }

        if (DirichletDatatype.URI.equals(dtype)) {
            DirichletValue dir = extractDirichlet(distNode);
            return NodeValue.makeString(formatVector(dir.std()));
        }

        throw new IllegalArgumentException("prob:std: unsupported distribution type: " + dtype);
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

    private double[] computeGMMVariance(GMMValue gmm) {
        int K = gmm.getK(), d = gmm.getD();
        double[] weights      = gmm.getWeights();
        double[][] means      = gmm.getMeans();
        double[][][] covs     = gmm.getCovariances();
        String covType        = gmm.getCovarianceType();
        double[] mean = new double[d];
        for (int i = 0; i < K; i++)
            for (int j = 0; j < d; j++)
                mean[j] += weights[i] * means[i][j];
        double[] secondMoment = new double[d];
        for (int i = 0; i < K; i++) {
            for (int j = 0; j < d; j++) {
                double covDiag = getCovDiagonal(covs[i], covType, j);
                secondMoment[j] += weights[i] * (covDiag + means[i][j] * means[i][j]);
            }
        }
        double[] variance = new double[d];
        for (int j = 0; j < d; j++)
            variance[j] = Math.max(0.0, secondMoment[j] - mean[j] * mean[j]);
        return variance;
    }

    private double getCovDiagonal(double[][] cov, String type, int idx) {
        return switch (type) {
            case "full"      -> cov[idx][idx];
            case "diag"      -> cov[0][idx];
            case "spherical" -> cov[0][0];
            default -> throw new IllegalStateException("Unknown covariance type: " + type);
        };
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
