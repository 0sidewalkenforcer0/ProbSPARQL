package org.apache.jena.probsparql.utils;

/**
 * Matrix and linear algebra utilities for multi-dimensional GMM operations.
 * 
 * <p>Provides numerical linear algebra operations needed for probabilistic computations:</p>
 * <ul>
 *   <li>Matrix inversion (LU decomposition)</li>
 *   <li>Determinant computation</li>
 *   <li>Matrix operations (trace, multiplication)</li>
 *   <li>Vector operations (dot product, quadratic forms)</li>
 *   <li>Cholesky decomposition</li>
 * </ul>
 * 
 * @author ProbSPARQL Team
 */
public class MatrixUtils {
    
    private static final double EPSILON = 1e-10;
    
    /**
     * Compute matrix inverse using LU decomposition.
     * 
     * @param matrix Square matrix to invert (d×d)
     * @param d Dimension
     * @return Inverse matrix
     * @throws IllegalArgumentException if matrix is singular
     */
    public static double[][] invertMatrix(double[][] matrix, int d) {
        // Create augmented matrix [A | I]
        double[][] augmented = new double[d][2 * d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                augmented[i][j] = matrix[i][j];
            }
            augmented[i][d + i] = 1.0;  // Identity on right
        }
        
        // Gaussian elimination with partial pivoting
        for (int i = 0; i < d; i++) {
            // Find pivot
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
            
            // Check for singularity
            if (Math.abs(augmented[i][i]) < EPSILON) {
                throw new IllegalArgumentException("Matrix is singular or nearly singular");
            }
            
            // Scale pivot row
            double pivot = augmented[i][i];
            for (int j = 0; j < 2 * d; j++) {
                augmented[i][j] /= pivot;
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
                inverse[i][j] = augmented[i][d + j];
            }
        }
        
        return inverse;
    }
    
    /**
     * Compute determinant using LU decomposition.
     * 
     * @param matrix Square matrix (d×d)
     * @param d Dimension
     * @return Determinant value
     */
    public static double determinant(double[][] matrix, int d) {
        if (d == 1) {
            return matrix[0][0];
        }
        
        if (d == 2) {
            return matrix[0][0] * matrix[1][1] - matrix[0][1] * matrix[1][0];
        }
        
        // LU decomposition for larger matrices
        double[][] lu = new double[d][d];
        for (int i = 0; i < d; i++) {
            System.arraycopy(matrix[i], 0, lu[i], 0, d);
        }
        
        double det = 1.0;
        
        for (int i = 0; i < d; i++) {
            // Find pivot
            int maxRow = i;
            for (int k = i + 1; k < d; k++) {
                if (Math.abs(lu[k][i]) > Math.abs(lu[maxRow][i])) {
                    maxRow = k;
                }
            }
            
            // Swap rows (affects determinant sign)
            if (maxRow != i) {
                double[] temp = lu[i];
                lu[i] = lu[maxRow];
                lu[maxRow] = temp;
                det *= -1.0;
            }
            
            // Check for singularity
            if (Math.abs(lu[i][i]) < EPSILON) {
                return 0.0;
            }
            
            det *= lu[i][i];
            
            // Eliminate below
            for (int k = i + 1; k < d; k++) {
                double factor = lu[k][i] / lu[i][i];
                for (int j = i; j < d; j++) {
                    lu[k][j] -= factor * lu[i][j];
                }
            }
        }
        
        return det;
    }
    
    /**
     * Compute matrix trace (sum of diagonal elements).
     * 
     * @param matrix Square matrix (d×d)
     * @param d Dimension
     * @return Trace value
     */
    public static double matrixTrace(double[][] matrix, int d) {
        double trace = 0.0;
        for (int i = 0; i < d; i++) {
            trace += matrix[i][i];
        }
        return trace;
    }
    
    /**
     * Multiply two matrices: C = A × B
     * 
     * @param A First matrix (d×d)
     * @param B Second matrix (d×d)
     * @param d Dimension
     * @return Product matrix C
     */
    public static double[][] matrixMultiply(double[][] A, double[][] B, int d) {
        double[][] C = new double[d][d];
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                C[i][j] = 0.0;
                for (int k = 0; k < d; k++) {
                    C[i][j] += A[i][k] * B[k][j];
                }
            }
        }
        return C;
    }
    
    /**
     * Compute quadratic form: x^T A x
     * 
     * @param x Vector (d×1)
     * @param A Matrix (d×d)
     * @param d Dimension
     * @return Scalar result
     */
    public static double quadraticForm(double[] x, double[][] A, int d) {
        double result = 0.0;
        for (int i = 0; i < d; i++) {
            for (int j = 0; j < d; j++) {
                result += x[i] * A[i][j] * x[j];
            }
        }
        return result;
    }
    
    /**
     * Subtract two vectors: result = v1 - v2
     * 
     * @param v1 First vector
     * @param v2 Second vector
     * @param d Dimension
     * @return Difference vector
     */
    public static double[] subtract(double[] v1, double[] v2, int d) {
        double[] result = new double[d];
        for (int i = 0; i < d; i++) {
            result[i] = v1[i] - v2[i];
        }
        return result;
    }
    
    /**
     * Cholesky decomposition: A = L L^T
     * 
     * <p>Decomposes a positive definite matrix into lower triangular form.</p>
     * 
     * @param matrix Positive definite matrix (d×d)
     * @param d Dimension
     * @return Lower triangular matrix L
     * @throws IllegalArgumentException if matrix is not positive definite
     */
    public static double[][] choleskyDecomposition(double[][] matrix, int d) {
        double[][] L = new double[d][d];
        
        for (int i = 0; i < d; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = 0.0;
                
                if (j == i) {
                    // Diagonal element
                    for (int k = 0; k < j; k++) {
                        sum += L[j][k] * L[j][k];
                    }
                    double val = matrix[j][j] - sum;
                    if (val <= 0) {
                        throw new IllegalArgumentException(
                            "Matrix is not positive definite at element [" + j + "," + j + "]");
                    }
                    L[j][j] = Math.sqrt(val);
                } else {
                    // Off-diagonal element
                    for (int k = 0; k < j; k++) {
                        sum += L[i][k] * L[j][k];
                    }
                    L[i][j] = (matrix[i][j] - sum) / L[j][j];
                }
            }
        }
        
        return L;
    }
    
    /**
     * Multiply matrix by vector: result = A × x
     * 
     * @param A Matrix (d×d)
     * @param x Vector (d×1)
     * @param d Dimension
     * @return Result vector
     */
    public static double[] matrixVectorMultiply(double[][] A, double[] x, int d) {
        double[] result = new double[d];
        for (int i = 0; i < d; i++) {
            result[i] = 0.0;
            for (int j = 0; j < d; j++) {
                result[i] += A[i][j] * x[j];
            }
        }
        return result;
    }
    
    /**
     * Compute dot product of two vectors.
     * 
     * @param v1 First vector
     * @param v2 Second vector
     * @param d Dimension
     * @return Dot product
     */
    public static double dotProduct(double[] v1, double[] v2, int d) {
        double result = 0.0;
        for (int i = 0; i < d; i++) {
            result += v1[i] * v2[i];
        }
        return result;
    }
}
