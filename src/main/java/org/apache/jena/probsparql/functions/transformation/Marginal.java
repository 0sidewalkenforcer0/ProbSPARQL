package org.apache.jena.probsparql.functions.transformation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramDatatype;
import org.apache.jena.probsparql.datatypes.HistogramOperations;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute the marginal distribution of a GMM or Histogram.
 * 
 * <p>Extracts one or more dimensions from a multivariate GMM or Histogram.
 * The second argument may be a numeric dimension index, e.g. {@code 0}, or a
 * JSON-array string of dimension indices, e.g. {@code "[0,2]"}.</p>
 * 
 * <p>For a GMM with d dimensions, marginalizing over selected dimensions gives:</p>
 * <ul>
 *   <li>K components (unchanged)</li>
 *   <li>Weights: unchanged</li>
 *   <li>Means: selected entries of μ for each component</li>
 *   <li>Covariances: selected covariance submatrix for each component</li>
 * </ul>
 *
 * <p>For a multidimensional Histogram, this sums probability masses over all
 * non-selected dimensions and returns a lower-dimensional Histogram.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?marginalDist WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:marginal(?gmm, "[0,2]") AS ?marginalDist)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Marginal extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#marginal";
    
    /**
     * Compute marginal distribution over specified dimensions.
     * 
     * @param distNode NodeValue containing a GMM or Histogram literal
     * @param dimNode NodeValue containing dimension index or JSON array of indices (0-based)
     * @return Marginal distribution literal of the same datatype
     */
    @Override
    public NodeValue exec(NodeValue distNode, NodeValue dimNode) {
        Object value = distNode.asNode().getLiteralValue();
        if (value instanceof HistogramValue histogram) {
            int[] dimensions = extractDimensions(dimNode, histogram.getDimensions());
            HistogramValue marginal = HistogramOperations.marginal(histogram, dimensions);
            org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
                marginal.toString(), HistogramDatatype.INSTANCE
            );
            return NodeValue.makeNode(node);
        }

        GMMValue gmm = extractGMM(distNode);
        int[] dimensions = extractDimensions(dimNode, gmm.getDimensions());
        GMMValue marginalGMM = computeMarginal(gmm, dimensions);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            marginalGMM.toJSON(), GMMDatatype.INSTANCE
        );
        return NodeValue.makeNode(node);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException("First argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "First argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    private int[] extractDimensions(NodeValue node, int maxDim) {
        int[] dimensions;
        if (node.isNumber()) {
            dimensions = new int[]{node.getInteger().intValue()};
        } else if (node.isString()) {
            dimensions = parseDimensionArray(node.getString());
        } else {
            throw new IllegalArgumentException(
                "Second argument must be a numeric dimension index or JSON array string");
        }

        if (dimensions.length == 0) {
            throw new IllegalArgumentException("At least one marginal dimension must be selected");
        }
        boolean[] seen = new boolean[maxDim];
        for (int dim : dimensions) {
            if (dim < 0 || dim >= maxDim) {
                throw new IllegalArgumentException(
                    "Dimension index " + dim + " out of range [0, " + (maxDim - 1) + "]");
            }
            if (seen[dim]) {
                throw new IllegalArgumentException("Duplicate marginal dimension: " + dim);
            }
            seen[dim] = true;
        }
        return dimensions;
    }

    private int[] parseDimensionArray(String text) {
        String trimmed = text.trim();
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) {
            throw new IllegalArgumentException("Dimension selection must be a JSON array, e.g. \"[0,2]\"");
        }
        String content = trimmed.substring(1, trimmed.length() - 1).trim();
        if (content.isEmpty()) {
            return new int[0];
        }
        String[] parts = content.split(",");
        int[] dimensions = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            dimensions[i] = Integer.parseInt(parts[i].trim());
        }
        return dimensions;
    }
    
    /**
     * Compute marginal distribution over specified dimensions.
     */
    private GMMValue computeMarginal(GMMValue gmm, int[] dimensions) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String covType = gmm.getCovarianceType();
        
        if (isIdentitySelection(dimensions, d)) {
            return gmm;
        }

        int outDimensions = dimensions.length;
        double[] weights = gmm.getWeights().clone();
        
        // Extract means for selected dimensions.
        double[][] means = new double[K][outDimensions];
        for (int k = 0; k < K; k++) {
            for (int outDim = 0; outDim < outDimensions; outDim++) {
                means[k][outDim] = gmm.getMeans()[k][dimensions[outDim]];
            }
        }
        
        // Extract covariance submatrix for selected dimensions.
        double[][][] covariances = new double[K][][];
        for (int k = 0; k < K; k++) {
            covariances[k] = extractCovarianceSubmatrix(gmm.getCovariances()[k], dimensions, covType);
        }
        
        return new GMMValue(K, outDimensions, "full", weights, means, covariances);
    }
    
    private double[][] extractCovarianceSubmatrix(double[][] cov, int[] dimensions, String covType) {
        int outDimensions = dimensions.length;
        double[][] out = new double[outDimensions][outDimensions];
        for (int i = 0; i < outDimensions; i++) {
            for (int j = 0; j < outDimensions; j++) {
                out[i][j] = covarianceAt(cov, dimensions[i], dimensions[j], covType);
            }
        }
        return out;
    }

    private double covarianceAt(double[][] cov, int row, int col, String covType) {
        switch (covType) {
            case "full":
                return cov[row][col];
            case "diag":
                return row == col ? cov[0][row] : 0.0;
            case "spherical":
                return row == col ? cov[0][0] : 0.0;
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
    }

    private boolean isIdentitySelection(int[] dimensions, int sourceDimensions) {
        if (dimensions.length != sourceDimensions) {
            return false;
        }
        for (int i = 0; i < dimensions.length; i++) {
            if (dimensions[i] != i) {
                return false;
            }
        }
        return true;
    }
}
