package org.apache.jena.probsparql.functions.thresholding;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to evaluate the Probability Density Function (PDF) 
 * of a Gaussian Mixture Model at a given point.
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?density WHERE {
 *   ?var uq:hasDistribution ?gmm .
 *   BIND(prob:pdf(?gmm, 6.0) AS ?density)
 * }
 * </pre>
 * 
 * <p>For a GMM with K components in d dimensions:</p>
 * <pre>
 * PDF(x) = Σ(k=1 to K) w_k * N(x | μ_k, Σ_k)
 * </pre>
 * where N(x | μ, Σ) is the multivariate Gaussian density.
 * 
 * @author ProbSPARQL Team
 */
public class PDF extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#pdf";
    
    /**
     * Evaluate PDF at a point.
     * 
     * @param gmmNode NodeValue containing GMM literal
     * @param pointNode NodeValue containing the evaluation point (scalar for d=1)
     * @return PDF value as double
     */
    @Override
    public NodeValue exec(NodeValue gmmNode, NodeValue pointNode) {
        // Extract GMM from first argument
        GMMValue gmm = extractGMM(gmmNode);
        
        // Extract evaluation point
        double[] point = extractPoint(pointNode, gmm.getDimensions());
        
        // Compute PDF
        double density = computePDF(gmm, point);
        
        return NodeValue.makeDouble(density);
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
                "First argument must be of type " + GMMDatatype.URI + 
                ", got: " + node.asNode().getLiteralDatatypeURI());
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Extract evaluation point from NodeValue.
     * For d=1, accepts a single number or JSON array "[x]"
     * For d>1, accepts JSON array "[x1, x2, ..., xd]"
     */
    private double[] extractPoint(NodeValue node, int d) {
        if (d == 1) {
            // For 1D, accept both number and JSON array
            if (node.isNumber()) {
                return new double[] { node.getDouble() };
            } else if (node.isString()) {
                return parseVector(node.getString(), d);
            } else {
                throw new IllegalArgumentException(
                    "Second argument must be a number or JSON array for 1D GMM");
            }
        } else {
            // For multi-D, require JSON array string
            if (!node.isString()) {
                throw new IllegalArgumentException(
                    "Second argument must be a JSON array string for " + d + "D GMM (e.g., \"[1.5, 2.3]\")");
            }
            return parseVector(node.getString(), d);
        }
    }
    
    /**
     * Parse vector from JSON array string: "[v1, v2, ..., vd]"
     */
    private double[] parseVector(String str, int d) {
        str = str.trim();
        if (!str.startsWith("[") || !str.endsWith("]")) {
            throw new IllegalArgumentException(
                "Vector must be JSON array format: [v1, v2, ...]");
        }
        
        String content = str.substring(1, str.length() - 1).trim();
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Empty vector not allowed");
        }
        
        String[] parts = content.split(",");
        if (parts.length != d) {
            throw new IllegalArgumentException(
                "Vector dimension mismatch: expected " + d + ", got " + parts.length);
        }
        
        double[] vector = new double[d];
        for (int i = 0; i < d; i++) {
            try {
                vector[i] = Double.parseDouble(parts[i].trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                    "Invalid number in vector at position " + i + ": " + parts[i]);
            }
        }
        
        return vector;
    }
    
    /**
     * Compute PDF value for a GMM at given point.
     * 
     * PDF(x) = Σ(k=1 to K) w_k * N(x | μ_k, Σ_k)
     */
    private double computePDF(GMMValue gmm, double[] point) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        double[] weights = gmm.getWeights();
        double[][] means = gmm.getMeans();
        double[][][] covariances = gmm.getCovariances();
        String covType = gmm.getCovarianceType();
        
        double pdfValue = 0.0;
        
        for (int k = 0; k < K; k++) {
            double componentDensity = evaluateGaussian(
                point, 
                means[k], 
                covariances[k],
                covType,
                d
            );
            pdfValue += weights[k] * componentDensity;
        }
        
        return pdfValue;
    }
    
    /**
     * Evaluate multivariate Gaussian density at a point.
     * 
     * N(x | μ, Σ) = (2π)^(-d/2) * |Σ|^(-1/2) * exp(-0.5 * (x-μ)^T Σ^(-1) (x-μ))
     */
    private double evaluateGaussian(double[] x, double[] mean, 
                                   double[][] covariance, 
                                   String covType, int d) {
        // Compute (x - μ)
        double[] diff = new double[d];
        for (int i = 0; i < d; i++) {
            diff[i] = x[i] - mean[i];
        }
        
        // Compute determinant and Mahalanobis distance based on covariance type
        double det;
        double mahalanobis;
        
        switch (covType) {
            case "full":
                det = determinant(covariance, d);
                mahalanobis = mahalanobisDistance(diff, covariance, d);
                break;
                
            case "diag":
                // For diagonal: det = product of diagonal elements
                // Mahalanobis = sum((x_i - μ_i)^2 / σ_i^2)
                det = 1.0;
                mahalanobis = 0.0;
                for (int i = 0; i < d; i++) {
                    double variance = covariance[0][i]; // Stored as [1][d] in our format
                    det *= variance;
                    mahalanobis += (diff[i] * diff[i]) / variance;
                }
                break;
                
            case "spherical":
                // For spherical: det = σ^d, Mahalanobis = ||x-μ||^2 / σ^2
                double variance = covariance[0][0]; // Scalar variance
                det = Math.pow(variance, d);
                mahalanobis = 0.0;
                for (int i = 0; i < d; i++) {
                    mahalanobis += diff[i] * diff[i];
                }
                mahalanobis /= variance;
                break;
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + covType);
        }
        
        // Compute normalization constant
        double normalization = Math.pow(2 * Math.PI, -d / 2.0) * Math.pow(det, -0.5);
        
        // Compute exponential term
        double exponent = Math.exp(-0.5 * mahalanobis);
        
        return normalization * exponent;
    }
    
    /**
     * Compute determinant of a d×d matrix.
     * Uses simple formula for 1×1 and 2×2, recursion for larger matrices.
     */
    private double determinant(double[][] matrix, int d) {
        if (d == 1) {
            return matrix[0][0];
        }
        
        if (d == 2) {
            return matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0];
        }
        
        // For d > 2, use Laplace expansion (simple but not optimal)
        double det = 0.0;
        for (int j = 0; j < d; j++) {
            det += Math.pow(-1, j) * matrix[0][j] * 
                   determinant(minor(matrix, 0, j, d), d - 1);
        }
        return det;
    }
    
    /**
     * Get minor matrix by removing row i and column j.
     */
    private double[][] minor(double[][] matrix, int row, int col, int d) {
        double[][] result = new double[d - 1][d - 1];
        int r = 0;
        for (int i = 0; i < d; i++) {
            if (i == row) continue;
            int c = 0;
            for (int j = 0; j < d; j++) {
                if (j == col) continue;
                result[r][c] = matrix[i][j];
                c++;
            }
            r++;
        }
        return result;
    }
    
    /**
     * Compute Mahalanobis distance: (x-μ)^T Σ^(-1) (x-μ)
     * Uses Cholesky decomposition to solve linear system.
     */
    private double mahalanobisDistance(double[] diff, double[][] covariance, int d) {
        // Solve Σ * y = diff for y, then compute diff^T * y
        double[] y = solveLinearSystem(covariance, diff, d);
        
        double distance = 0.0;
        for (int i = 0; i < d; i++) {
            distance += diff[i] * y[i];
        }
        return distance;
    }
    
    /**
     * Solve Ax = b using Cholesky decomposition.
     * A must be symmetric positive definite.
     */
    private double[] solveLinearSystem(double[][] A, double[] b, int d) {
        // Compute Cholesky decomposition: A = L * L^T
        double[][] L = choleskyDecomposition(A, d);
        
        // Solve L * y = b (forward substitution)
        double[] y = new double[d];
        for (int i = 0; i < d; i++) {
            double sum = 0.0;
            for (int j = 0; j < i; j++) {
                sum += L[i][j] * y[j];
            }
            y[i] = (b[i] - sum) / L[i][i];
        }
        
        // Solve L^T * x = y (backward substitution)
        double[] x = new double[d];
        for (int i = d - 1; i >= 0; i--) {
            double sum = 0.0;
            for (int j = i + 1; j < d; j++) {
                sum += L[j][i] * x[j];
            }
            x[i] = (y[i] - sum) / L[i][i];
        }
        
        return x;
    }
    
    /**
     * Cholesky decomposition: A = L * L^T
     * Returns lower triangular matrix L.
     */
    private double[][] choleskyDecomposition(double[][] A, int d) {
        double[][] L = new double[d][d];
        
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }
                
                if (i == j) {
                    L[i][j] = Math.sqrt(A[i][i] - sum);
                } else {
                    L[i][j] = (A[i][j] - sum) / L[j][j];
                }
            }
        }
        
        return L;
    }
}
