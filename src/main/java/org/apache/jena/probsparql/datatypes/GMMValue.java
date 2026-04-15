package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a Gaussian Mixture Model (GMM) value.
 * 
 * A well-formed GMM literal must contain exactly six top-level fields in this order:
 * "n_components", "dimensions", "covariance_type", "weights", "means", and "covariances".
 * 
 * @author ProbSPARQL Team
 * @version 1.0.0
 */
public class GMMValue implements Sampleable {
    
    /** Number of mixture components */
    private final int nComponents;
    
    /** Dimensionality of the GMM */
    private final int dimensions;
    
    /** Type of covariance matrix (e.g., "full", "diagonal", "spherical") */
    private final String covarianceType;
    
    /** Component weights (must sum to 1.0) */
    private final double[] weights;
    
    /** Component means [K][d] */
    private final double[][] means;
    
    /** Component covariances [K][d][d] */
    private final double[][][] covariances;
    
    /**
     * Construct a GMM value.
     * 
     * @param nComponents number of mixture components
     * @param dimensions dimensionality
     * @param covarianceType covariance matrix type
     * @param weights component weights
     * @param means component means
     * @param covariances component covariances
     * @throws IllegalArgumentException if validation fails
     */
    public GMMValue(int nComponents, int dimensions, String covarianceType,
                    double[] weights, double[][] means, double[][][] covariances) {
        if (nComponents <= 0) {
            throw new IllegalArgumentException("K must be positive, got: " + nComponents);
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive, got: " + dimensions);
        }
        if (covarianceType == null || covarianceType.trim().isEmpty()) {
            throw new IllegalArgumentException("covariance_type cannot be null or empty");
        }
        if (weights == null || weights.length != nComponents) {
            throw new IllegalArgumentException("weights array must have length nComponents=" + nComponents);
        }
        if (means == null || means.length != nComponents) {
            throw new IllegalArgumentException("means array must have length nComponents=" + nComponents);
        }
        if (covariances == null || covariances.length != nComponents) {
            throw new IllegalArgumentException("covariances array must have length nComponents=" + nComponents);
        }
        
        // Validate weights sum to 1.0 (with tolerance for floating point errors)
        double weightSum = 0.0;
        for (double w : weights) {
            if (w < 0.0 || w > 1.0) {
                throw new IllegalArgumentException("All weights must be in [0, 1], got: " + w);
            }
            weightSum += w;
        }
        if (Math.abs(weightSum - 1.0) > 1e-4) {
            throw new IllegalArgumentException("Weights must sum to 1.0, got: " + weightSum);
        }
        
        // Validate means dimensions
        for (int i = 0; i < nComponents; i++) {
            if (means[i] == null || means[i].length != dimensions) {
                throw new IllegalArgumentException(
                    "means[" + i + "] must have length dimensions=" + dimensions);
            }
        }
        
        // Validate covariances based on covariance_type
        validateCovariances(nComponents, dimensions, covarianceType, covariances);
        
        this.nComponents = nComponents;
        this.dimensions = dimensions;
        this.covarianceType = covarianceType;
        this.weights = Arrays.copyOf(weights, weights.length);
        this.means = deepCopy2D(means);
        this.covariances = deepCopy3D(covariances);
    }
    
    // Getters
    
    public int getNComponents() {
        return nComponents;
    }
    
    public int getDimensions() {
        return dimensions;
    }
    
    public String getCovarianceType() {
        return covarianceType;
    }
    
    public double[] getWeights() {
        return Arrays.copyOf(weights, weights.length);
    }
    
    public double[][] getMeans() {
        return deepCopy2D(means);
    }
    
    public double[][][] getCovariances() {
        return deepCopy3D(covariances);
    }
    
    /**
     * Convert to JSON string representation.
     * Fields are ordered: n_components, dimensions, covariance_type, weights, means, covariances
     * 
     * @return JSON string
     */
    public String toJSON() {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"n_components\":").append(nComponents).append(",");
        sb.append("\"dimensions\":").append(dimensions).append(",");
        sb.append("\"covariance_type\":\"").append(covarianceType).append("\",");
        sb.append("\"weights\":").append(arrayToJSON(weights)).append(",");
        sb.append("\"means\":").append(array2DToJSON(means)).append(",");
        sb.append("\"covariances\":").append(covariancesToJSON());
        sb.append("}");
        return sb.toString();
    }
    
    private String arrayToJSON(double[] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arr[i]);
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String array2DToJSON(double[][] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(arrayToJSON(arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }
    
    private String array3DToJSON(double[][][] arr) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < arr.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(array2DToJSON(arr[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    private String covariancesToJSON() {
        return switch (covarianceType) {
            case "full" -> array3DToJSON(covariances);
            case "diag" -> {
                double[][] diag = new double[nComponents][];
                for (int i = 0; i < nComponents; i++) {
                    diag[i] = Arrays.copyOf(covariances[i][0], covariances[i][0].length);
                }
                yield array2DToJSON(diag);
            }
            case "spherical" -> {
                double[] spherical = new double[nComponents];
                for (int i = 0; i < nComponents; i++) {
                    spherical[i] = covariances[i][0][0];
                }
                yield arrayToJSON(spherical);
            }
            default -> throw new IllegalStateException("Unknown covariance type: " + covarianceType);
        };
    }
    
    private static double[][] deepCopy2D(double[][] original) {
        double[][] copy = new double[original.length][];
        for (int i = 0; i < original.length; i++) {
            copy[i] = Arrays.copyOf(original[i], original[i].length);
        }
        return copy;
    }
    
    private static double[][][] deepCopy3D(double[][][] original) {
        double[][][] copy = new double[original.length][][];
        for (int i = 0; i < original.length; i++) {
            copy[i] = deepCopy2D(original[i]);
        }
        return copy;
    }
    
    /**
     * Validate covariances based on covariance type.
     */
    private static void validateCovariances(int K, int d, String covarianceType, double[][][] covariances) {
        switch (covarianceType.toLowerCase()) {
            case "full":
                validateFullCovariances(K, d, covariances);
                break;
            case "diag":
                validateDiagCovariances(K, d, covariances);
                break;
            case "spherical":
                validateSphericalCovariances(K, covariances);
                break;
            default:
                throw new IllegalArgumentException(
                    "covariance_type must be 'full', 'diag', or 'spherical', got: '" + covarianceType + "'");
        }
    }
    
    /**
     * Validate full covariance matrices (d×d matrices).
     */
    private static void validateFullCovariances(int K, int d, double[][][] covariances) {
        for (int i = 0; i < K; i++) {
            if (covariances[i] == null || covariances[i].length != d) {
                throw new IllegalArgumentException(
                    "For 'full' covariance: covariances[" + i + "] must be " + d + "×" + d + " matrix, got first dimension: " + 
                    (covariances[i] == null ? "null" : covariances[i].length));
            }
            for (int j = 0; j < d; j++) {
                if (covariances[i][j] == null || covariances[i][j].length != d) {
                    throw new IllegalArgumentException(
                        "For 'full' covariance: covariances[" + i + "][" + j + "] must have length dimensions=" + d);
                }
            }
            
            // Check symmetry and positive semi-definiteness
            if (!isSymmetric(covariances[i])) {
                throw new IllegalArgumentException(
                    "covariances[" + i + "] must be symmetric");
            }
            if (!isPositiveSemiDefinite(covariances[i])) {
                throw new IllegalArgumentException(
                    "covariances[" + i + "] must be positive semi-definite");
            }
        }
    }
    
    /**
     * Validate diagonal covariances (d-dimensional vectors).
     */
    private static void validateDiagCovariances(int K, int d, double[][][] covariances) {
        for (int i = 0; i < K; i++) {
            if (covariances[i] == null || covariances[i].length != 1) {
                throw new IllegalArgumentException(
                    "For 'diag' covariance: covariances[" + i + "] must contain 1 array, got: " +
                    (covariances[i] == null ? "null" : covariances[i].length));
            }
            if (covariances[i][0] == null || covariances[i][0].length != d) {
                throw new IllegalArgumentException(
                    "For 'diag' covariance: covariances[" + i + "][0] must have length dimensions=" + d + ", got: " +
                    (covariances[i][0] == null ? "null" : covariances[i][0].length));
            }
            
            // Check strictly positive variances
            for (int j = 0; j < d; j++) {
                if (covariances[i][0][j] <= 0.0) {
                    throw new IllegalArgumentException(
                        "For 'diag' covariance: all variances must be strictly positive, " +
                        "got covariances[" + i + "][0][" + j + "] = " + covariances[i][0][j]);
                }
            }
        }
    }
    
    /**
     * Validate spherical covariances (single scalar per component).
     */
    private static void validateSphericalCovariances(int K, double[][][] covariances) {
        for (int i = 0; i < K; i++) {
            if (covariances[i] == null || covariances[i].length != 1) {
                throw new IllegalArgumentException(
                    "For 'spherical' covariance: covariances[" + i + "] must contain 1 array, got: " +
                    (covariances[i] == null ? "null" : covariances[i].length));
            }
            if (covariances[i][0] == null || covariances[i][0].length != 1) {
                throw new IllegalArgumentException(
                    "For 'spherical' covariance: covariances[" + i + "][0] must contain 1 scalar, got: " +
                    (covariances[i][0] == null ? "null" : covariances[i][0].length));
            }
            
            // Check strictly positive variance
            if (covariances[i][0][0] <= 0.0) {
                throw new IllegalArgumentException(
                    "For 'spherical' covariance: variance must be strictly positive, " +
                    "got covariances[" + i + "][0][0] = " + covariances[i][0][0]);
            }
        }
    }
    
    /**
     * Check if a matrix is symmetric.
     */
    private static boolean isSymmetric(double[][] matrix) {
        int d = matrix.length;
        for (int i = 0; i < d; i++) {
            for (int j = i + 1; j < d; j++) {
                if (Math.abs(matrix[i][j] - matrix[j][i]) > 1e-10) {
                    return false;
                }
            }
        }
        return true;
    }
    
    /**
     * Check if a matrix is positive semi-definite using Cholesky decomposition.
     * A symmetric matrix is PSD iff its Cholesky decomposition exists.
     */
    private static boolean isPositiveSemiDefinite(double[][] matrix) {
        int d = matrix.length;
        double[][] L = new double[d][d];
        
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                for (int k = 0; k < j; k++) {
                    sum += L[i][k] * L[j][k];
                }
                
                if (i == j) {
                    double diag = matrix[i][i] - sum;
                    if (diag < -1e-10) {  // Allow small numerical errors
                        return false;
                    }
                    L[i][j] = Math.sqrt(Math.max(0.0, diag));
                } else {
                    if (Math.abs(L[j][j]) < 1e-10) {
                        // Avoid division by very small number
                        L[i][j] = 0.0;
                    } else {
                        L[i][j] = (matrix[i][j] - sum) / L[j][j];
                    }
                }
            }
        }
        
        return true;
    }
    
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        GMMValue gmmValue = (GMMValue) o;
        return nComponents == gmmValue.nComponents &&
               dimensions == gmmValue.dimensions &&
               covarianceType.equals(gmmValue.covarianceType) &&
               Arrays.equals(weights, gmmValue.weights) &&
               Arrays.deepEquals(means, gmmValue.means) &&
               Arrays.deepEquals(covariances, gmmValue.covariances);
    }
    
    @Override
    public int hashCode() {
        int result = Objects.hash(nComponents, dimensions, covarianceType);
        result = 31 * result + Arrays.hashCode(weights);
        result = 31 * result + Arrays.deepHashCode(means);
        result = 31 * result + Arrays.deepHashCode(covariances);
        return result;
    }
    
    @Override
    public String toString() {
        return "GMMValue{n_components=" + nComponents + ", dimensions=" + dimensions + ", covariance_type='" + covarianceType + "'}";
    }

    // -----------------------------------------------------------------------
    // Sampleable implementation
    // -----------------------------------------------------------------------

    /**
     * Draw n samples from this GMM: first select component proportional to weight,
     * then sample from the corresponding Gaussian.
     */
    @Override
    public double[][] sample(int n) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[][] samples = new double[n][dimensions];
        for (int s = 0; s < n; s++) {
            // Select component
            int k = selectComponent(rng);
            // Sample from Gaussian component k
            samples[s] = sampleGaussian(rng, means[k], covariances[k], covarianceType, dimensions);
        }
        return samples;
    }

    /**
     * Draw a single sample using a caller-provided RNG. This keeps higher-level
     * sampling methods reproducible without duplicating GMM sampling logic.
     */
    public double[] sampleOne(Random rng) {
        int k = selectComponent(rng);
        return sampleGaussian(rng, means[k], covariances[k], covarianceType, dimensions);
    }

    /**
     * Draw a single sample from a specific mixture component using a caller-provided RNG.
     */
    public double[] sampleComponent(int component, Random rng) {
        if (component < 0 || component >= nComponents) {
            throw new IllegalArgumentException("component index out of range: " + component);
        }
        return sampleGaussian(rng, means[component], covariances[component], covarianceType, dimensions);
    }

    /**
     * Evaluate log-density at point x: log sum_k w_k * N(x | mu_k, Sigma_k)
     * Uses log-sum-exp for numerical stability.
     */
    @Override
    public double logPdf(double[] x) {
        double[] logTerms = new double[nComponents];
        for (int k = 0; k < nComponents; k++) {
            logTerms[k] = Math.log(weights[k]) + logGaussianDensity(x, means[k], covariances[k], covarianceType, dimensions);
        }
        return logSumExp(logTerms);
    }

    private int selectComponent(ThreadLocalRandom rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        for (int k = 0; k < nComponents - 1; k++) {
            cumulative += weights[k];
            if (u < cumulative) return k;
        }
        return nComponents - 1;
    }

    private int selectComponent(Random rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        for (int k = 0; k < nComponents - 1; k++) {
            cumulative += weights[k];
            if (u < cumulative) return k;
        }
        return nComponents - 1;
    }

    private static double[] sampleGaussian(ThreadLocalRandom rng, double[] mu,
                                            double[][] cov, String covType, int d) {
        double[] z = new double[d];
        for (int j = 0; j < d; j++) z[j] = rng.nextGaussian();
        // Apply covariance transform
        switch (covType.toLowerCase()) {
            case "spherical" -> {
                double std = Math.sqrt(cov[0][0]);
                for (int j = 0; j < d; j++) z[j] = mu[j] + std * z[j];
            }
            case "diag" -> {
                for (int j = 0; j < d; j++) z[j] = mu[j] + Math.sqrt(cov[0][j]) * z[j];
            }
            default -> {
                // full: Cholesky decomposition L such that L*L' = cov
                double[][] L = cholesky(cov, d);
                double[] x = new double[d];
                for (int i = 0; i < d; i++) {
                    x[i] = mu[i];
                    for (int j = 0; j <= i; j++) x[i] += L[i][j] * z[j];
                }
                return x;
            }
        }
        return z;
    }

    private static double[] sampleGaussian(Random rng, double[] mu,
                                           double[][] cov, String covType, int d) {
        double[] z = new double[d];
        for (int j = 0; j < d; j++) z[j] = rng.nextGaussian();
        switch (covType.toLowerCase()) {
            case "spherical" -> {
                double std = Math.sqrt(cov[0][0]);
                for (int j = 0; j < d; j++) z[j] = mu[j] + std * z[j];
            }
            case "diag" -> {
                for (int j = 0; j < d; j++) z[j] = mu[j] + Math.sqrt(cov[0][j]) * z[j];
            }
            default -> {
                double[][] L = cholesky(cov, d);
                double[] x = new double[d];
                for (int i = 0; i < d; i++) {
                    x[i] = mu[i];
                    for (int j = 0; j <= i; j++) x[i] += L[i][j] * z[j];
                }
                return x;
            }
        }
        return z;
    }

    private static double logGaussianDensity(double[] x, double[] mu, double[][] cov,
                                              String covType, int d) {
        double mahal;
        double logDet;
        switch (covType.toLowerCase()) {
            case "spherical" -> {
                double var = cov[0][0];
                double diff = 0.0;
                for (int j = 0; j < d; j++) { double v = x[j] - mu[j]; diff += v * v; }
                mahal  = diff / var;
                logDet = d * Math.log(var);
            }
            case "diag" -> {
                mahal  = 0.0;
                logDet = 0.0;
                for (int j = 0; j < d; j++) {
                    mahal  += (x[j] - mu[j]) * (x[j] - mu[j]) / cov[0][j];
                    logDet += Math.log(cov[0][j]);
                }
            }
            default -> {
                // full: solve cov * v = (x - mu) via Cholesky
                double[][] L = cholesky(cov, d);
                double[] diff = new double[d];
                for (int j = 0; j < d; j++) diff[j] = x[j] - mu[j];
                double[] v = forwardSolve(L, diff, d);
                mahal  = 0.0;
                for (double vi : v) mahal += vi * vi;
                logDet = 0.0;
                for (int j = 0; j < d; j++) logDet += 2.0 * Math.log(L[j][j] + 1e-300);
            }
        }
        return -0.5 * (d * Math.log(2 * Math.PI) + logDet + mahal);
    }

    private static double[][] cholesky(double[][] A, int d) {
        double[][] L = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double s = A[i][j];
                for (int k = 0; k < j; k++) s -= L[i][k] * L[j][k];
                L[i][j] = (i == j) ? Math.sqrt(Math.max(s, 1e-300)) : s / L[j][j];
            }
        }
        return L;
    }

    private static double[] forwardSolve(double[][] L, double[] b, int d) {
        double[] y = new double[d];
        for (int i = 0; i < d; i++) {
            y[i] = b[i];
            for (int j = 0; j < i; j++) y[i] -= L[i][j] * y[j];
            y[i] /= (L[i][i] + 1e-300);
        }
        return y;
    }

    private static double logSumExp(double[] vals) {
        double max = Double.NEGATIVE_INFINITY;
        for (double v : vals) if (v > max) max = v;
        if (Double.isInfinite(max)) return Double.NEGATIVE_INFINITY;
        double sum = 0.0;
        for (double v : vals) sum += Math.exp(v - max);
        return max + Math.log(sum);
    }
}
