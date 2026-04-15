package org.apache.jena.probsparql.functions.manipulation;

import org.apache.jena.probsparql.datatypes.GMMDatatype;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.sparql.expr.NodeValue;
import org.apache.jena.sparql.function.FunctionBase2;

/**
 * SPARQL function to compute Bayesian fusion of two GMMs.
 * 
 * <p>For prior p(x) and likelihood p(y|x), computes posterior:</p>
 * <pre>
 * p(x|y) ∝ p(y|x) * p(x)
 * </pre>
 * 
 * <p>Uses the closed-form Gaussian product formula for each component pair:</p>
 * <ul>
 *   <li>K_posterior = K_prior × K_likelihood</li>
 *   <li>Σ_ij = (Σ_prior_i^{-1} + Σ_likelihood_j^{-1})^{-1}</li>
 *   <li>μ_ij = Σ_ij * (Σ_prior_i^{-1}*μ_prior_i + Σ_likelihood_j^{-1}*μ_likelihood_j)</li>
 *   <li>w_ij = c_ij * w_prior_i * w_likelihood_j (normalized)</li>
 * </ul>
 * 
 * <p>where c_ij = N(μ_prior_i; μ_likelihood_j, Σ_prior_i + Σ_likelihood_j)
 * is the consistency measure.</p>
 * 
 * <p>This is the foundation of Kalman filtering and Bayesian inference.</p>
 * 
 * <p>Usage in SPARQL:</p>
 * <pre>
 * PREFIX prob: &lt;http://probsparql.org/function#&gt;
 * SELECT ?posterior WHERE {
 *   ?state uq:hasDistribution ?prior .
 *   ?measurement uq:hasDistribution ?likelihood .
 *   BIND(prob:fuse(?prior, ?likelihood) AS ?posterior)
 * }
 * </pre>
 * 
 * @author ProbSPARQL Team
 */
public class Fuse extends FunctionBase2 {
    
    public static final String URI = "http://probsparql.org/function#fuse";
    
    private static final double TWO_PI = 2.0 * Math.PI;
    
    /**
     * Compute Bayesian fusion (product) of two GMMs.
     * 
     * @param priorNode Prior GMM p(x)
     * @param likelihoodNode Likelihood GMM p(y|x)
     * @return Posterior GMM p(x|y)
     */
    @Override
    public NodeValue exec(NodeValue priorNode, NodeValue likelihoodNode) {
        GMMValue prior = extractGMM(priorNode, "prior");
        GMMValue likelihood = extractGMM(likelihoodNode, "likelihood");
        
        // Validate compatibility
        if (prior.getDimensions() != likelihood.getDimensions()) {
            throw new IllegalArgumentException(
                "GMMs must have same dimensionality for fusion. Got d_prior=" + 
                prior.getDimensions() + ", d_likelihood=" + likelihood.getDimensions());
        }
        
        GMMValue posterior = computeFusion(prior, likelihood);
        
        org.apache.jena.graph.Node node = org.apache.jena.graph.NodeFactory.createLiteralDT(
            posterior.toJSON(), GMMDatatype.INSTANCE
        );
        return NodeValue.makeNode(node);
    }
    
    /**
     * Extract GMMValue from NodeValue.
     */
    private GMMValue extractGMM(NodeValue node, String position) {
        if (!node.isLiteral()) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be a GMM literal");
        }
        
        Object value = node.asNode().getLiteralValue();
        if (!(value instanceof GMMValue)) {
            throw new IllegalArgumentException(
                "The " + position + " argument must be of type " + GMMDatatype.URI);
        }
        
        return (GMMValue) value;
    }
    
    /**
     * Compute Bayesian fusion using component-wise Gaussian product.
     * 
     * For each pair (i,j), compute:
     * - Σ_ij = (Σ1_i^{-1} + Σ2_j^{-1})^{-1}
     * - μ_ij = Σ_ij * (Σ1_i^{-1}*μ1_i + Σ2_j^{-1}*μ2_j)
     * - w_ij = c_ij * w1_i * w2_j
     */
    private GMMValue computeFusion(GMMValue prior, GMMValue likelihood) {
        int K1 = prior.getNComponents();
        int K2 = likelihood.getNComponents();
        int d = prior.getDimensions();
        
        int K = K1 * K2;
        
        double[] weights = new double[K];
        double[][] means = new double[K][d];
        double[][][] covariances = new double[K][][];
        
        // Convert to full covariance matrices
        double[][][] cov1Full = toFullCovariances(prior);
        double[][][] cov2Full = toFullCovariances(likelihood);
        
        int idx = 0;
        double weightSum = 0.0;
        
        for (int i = 0; i < K1; i++) {
            for (int j = 0; j < K2; j++) {
                // Compute Gaussian product
                GaussianProduct product = multiplyGaussians(
                    prior.getMeans()[i],
                    cov1Full[i],
                    likelihood.getMeans()[j],
                    cov2Full[j],
                    d
                );
                
                // Weight includes consistency measure
                weights[idx] = product.consistency * 
                               prior.getWeights()[i] * 
                               likelihood.getWeights()[j];
                weightSum += weights[idx];
                
                means[idx] = product.mean;
                covariances[idx] = product.covariance;
                
                idx++;
            }
        }
        
        // Normalize weights
        if (weightSum > 0) {
            for (int i = 0; i < K; i++) {
                weights[i] /= weightSum;
            }
        }
        
        return new GMMValue(K, d, "full", weights, means, covariances);
    }
    
    /**
     * Result of Gaussian product.
     */
    private static class GaussianProduct {
        double[] mean;
        double[][] covariance;
        double consistency;  // Normalization constant
    }
    
    /**
     * Multiply two Gaussians using closed-form formula.
     */
    private GaussianProduct multiplyGaussians(
            double[] mu1, double[][] Sigma1,
            double[] mu2, double[][] Sigma2,
            int d) {
        
        GaussianProduct result = new GaussianProduct();
        
        // Compute precision matrices (inverse covariances)
        double[][] Lambda1 = invertMatrix(Sigma1, d);
        double[][] Lambda2 = invertMatrix(Sigma2, d);
        
        // Posterior precision: Λ = Λ1 + Λ2
        double[][] Lambda = addMatrices(Lambda1, Lambda2, d);
        
        // Posterior covariance: Σ = Λ^{-1}
        result.covariance = invertMatrix(Lambda, d);
        
        // Precision-weighted means
        double[] Lambda1_mu1 = multiplyMatrixVector(Lambda1, mu1, d);
        double[] Lambda2_mu2 = multiplyMatrixVector(Lambda2, mu2, d);
        
        // Sum of precision-weighted means
        double[] Lambda_mu = addVectors(Lambda1_mu1, Lambda2_mu2, d);
        
        // Posterior mean: μ = Σ * (Λ1*μ1 + Λ2*μ2)
        result.mean = multiplyMatrixVector(result.covariance, Lambda_mu, d);
        
        // Consistency (normalization constant)
        // c = N(μ1; μ2, Σ1 + Σ2)
        double[][] SigmaSum = addMatrices(Sigma1, Sigma2, d);
        double[] muDiff = subtractVectors(mu1, mu2, d);
        result.consistency = gaussianPDF(muDiff, SigmaSum, d);
        
        return result;
    }
    
    /**
     * Evaluate Gaussian PDF at zero (for consistency measure).
     */
    private double gaussianPDF(double[] diff, double[][] Sigma, int d) {
        try {
            double[][] SigmaInv = invertMatrix(Sigma, d);
            double det = determinant(Sigma, d);
            
            if (det <= 0) {
                return 1e-10; // Avoid numerical issues
            }
            
            // Mahalanobis distance: diff' * Σ^{-1} * diff
            double mahalanobis = 0.0;
            for (int i = 0; i < d; i++) {
                double sum = 0.0;
                for (int j = 0; j < d; j++) {
                    sum += SigmaInv[i][j] * diff[j];
                }
                mahalanobis += diff[i] * sum;
            }
            
            double normConst = Math.pow(TWO_PI, -d / 2.0) * Math.pow(det, -0.5);
            return normConst * Math.exp(-0.5 * mahalanobis);
            
        } catch (Exception e) {
            return 1e-10; // Fallback for numerical issues
        }
    }
    
    /**
     * Convert GMM covariances to full matrices.
     */
    private double[][][] toFullCovariances(GMMValue gmm) {
        int K = gmm.getNComponents();
        int d = gmm.getDimensions();
        String type = gmm.getCovarianceType();
        double[][][] covs = gmm.getCovariances();
        
        double[][][] full = new double[K][][];
        for (int i = 0; i < K; i++) {
            full[i] = toFullCovariance(covs[i], type, d);
        }
        return full;
    }
    
    /**
     * Convert single covariance to full form.
     */
    private double[][] toFullCovariance(double[][] cov, String type, int d) {
        double[][] full = new double[d][d];
        
        switch (type) {
            case "full":
                for (int i = 0; i < d; i++) {
                    full[i] = cov[i].clone();
                }
                return full;
                
            case "diag":
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? cov[i][0] : 0.0;
                    }
                }
                return full;
                
            case "spherical":
                double variance = cov[0][0];
                for (int i = 0; i < d; i++) {
                    for (int j = 0; j < d; j++) {
                        full[i][j] = (i == j) ? variance : 0.0;
                    }
                }
                return full;
                
            default:
                throw new IllegalStateException("Unknown covariance type: " + type);
        }
    }
    
    /**
     * Matrix inversion using Cholesky decomposition for symmetric positive definite matrices.
     */
    private double[][] invertMatrix(double[][] A, int d) {
        // Simple Gauss-Jordan elimination for small matrices
        double[][] augmented = new double[d][2 * d];
        
        // Create augmented matrix [A | I]
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                augmented[i][j] = A[i][j];
                augmented[i][j + d] = (i == j) ? 1.0 : 0.0;
            }
        }
        
        // Forward elimination
        for (int i = 0; i < d; i++) {
            // Partial pivoting
            int maxRow = i;
            for (int k = i + 1; k < d; k++) {
                if (Math.abs(augmented[k][i]) > Math.abs(augmented[maxRow][i])) {
                    maxRow = k;
                }
            }
            
            // Swap rows
            double[] temp = augmented[i];
            augmented[i] = augmented[maxRow];
            augmented[maxRow] = temp;
            
            // Make diagonal 1
            double diag = augmented[i][i];
            if (Math.abs(diag) < 1e-10) {
                throw new RuntimeException("Matrix is singular");
            }
            
            for (int j = 0; j < 2 * d; j++) {
                augmented[i][j] /= diag;
            }
            
            // Eliminate column
            for (int k = 0; k < d; k++) {
                if (k != i) {
                    double factor = augmented[k][i];
                    for (int j = 0; j < 2 * d; j++) {
                        augmented[k][j] -= factor * augmented[i][j];
                    }
                }
            }
        }
        
        // Extract inverse from right half
        double[][] inverse = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                inverse[i][j] = augmented[i][j + d];
            }
        }
        
        return inverse;
    }
    
    /**
     * Compute determinant of matrix.
     */
    private double determinant(double[][] A, int d) {
        if (d == 1) {
            return A[0][0];
        }
        if (d == 2) {
            return A[0][0] * A[1][1] - A[0][1] * A[1][0];
        }
        
        // LU decomposition for larger matrices
        double[][] LU = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                LU[i][j] = A[i][j];
            }
        }
        
        double det = 1.0;
        for (int i = 0; i < d; i++) {
            // Partial pivoting
            int maxRow = i;
            for (int k = i + 1; k < d; k++) {
                if (Math.abs(LU[k][i]) > Math.abs(LU[maxRow][i])) {
                    maxRow = k;
                }
            }
            
            if (maxRow != i) {
                double[] temp = LU[i];
                LU[i] = LU[maxRow];
                LU[maxRow] = temp;
                det = -det; // Swap changes sign
            }
            
            if (Math.abs(LU[i][i]) < 1e-10) {
                return 0.0;
            }
            
            det *= LU[i][i];
            
            // Elimination
            for (int k = i + 1; k < d; k++) {
                double factor = LU[k][i] / LU[i][i];
                for (int j = i; j < d; j++) {
                    LU[k][j] -= factor * LU[i][j];
                }
            }
        }
        
        return det;
    }
    
    // Vector/Matrix operations
    
    private double[][] addMatrices(double[][] A, double[][] B, int d) {
        double[][] C = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                C[i][j] = A[i][j] + B[i][j];
            }
        }
        return C;
    }
    
    private double[] addVectors(double[] a, double[] b, int d) {
        double[] c = new double[d];
        for (int i = 0; i < d; i++) {
            c[i] = a[i] + b[i];
        }
        return c;
    }
    
    private double[] subtractVectors(double[] a, double[] b, int d) {
        double[] c = new double[d];
        for (int i = 0; i < d; i++) {
            c[i] = a[i] - b[i];
        }
        return c;
    }
    
    private double[] multiplyMatrixVector(double[][] A, double[] x, int d) {
        double[] y = new double[d];
        for (int i = 0; i < d; i++) {
            y[i] = 0.0;
            for (int j = 0; j < d; j++) {
                y[i] += A[i][j] * x[j];
            }
        }
        return y;
    }
}
