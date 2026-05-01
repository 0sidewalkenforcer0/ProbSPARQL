package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a Dirichlet distribution Dir(α₁, …, αₖ) over the (k-1)-simplex.
 *
 * <p>JSON literal format: {@code {"alphas":[2.5,1.0,3.0,0.5]}}</p>
 *
 * <p>The Dirichlet distribution is parameterised by a concentration vector α ∈ ℝᵏ, αᵢ > 0.
 * Its density over the simplex Δₖ = {x : xᵢ ≥ 0, Σxᵢ = 1} is:
 * <pre>
 *   p(x | α) = (1 / B(α)) · Π xᵢ^(αᵢ-1)
 * </pre>
 * where B(α) = Π Γ(αᵢ) / Γ(Σαᵢ) is the multivariate Beta function.</p>
 *
 * <p>Implements {@link Sampleable} so it can participate in cross-type JSD estimation.</p>
 */
public class DirichletValue implements Sampleable {

    private final double[] alphas;

    public DirichletValue(double[] alphas) {
        int dimensions = alphas == null ? 0 : alphas.length;
        if (dimensions < 2) throw new IllegalArgumentException("Dirichlet dimension must be at least 2");
        if (alphas == null || alphas.length != dimensions)
            throw new IllegalArgumentException("alphas must have length dimensions=" + dimensions);
        for (double a : alphas) {
            if (a <= 0.0) throw new IllegalArgumentException("All alpha values must be positive");
        }
        this.alphas = alphas.clone();
    }

    // -----------------------------------------------------------------------
    // Accessors
    // -----------------------------------------------------------------------

    public int      getDimensions() { return alphas.length; }
    public double[] getAlphas()     { return alphas.clone(); }
    public double   getAlphasSum()  { return sumAlphas(); }

    // -----------------------------------------------------------------------
    // Statistical summary
    // -----------------------------------------------------------------------

    /**
     * Component-wise mean: E[Xᵢ] = αᵢ / α₀.
     *
     * @return array of length dimensions
     */
    public double[] mean() {
        int dimensions = getDimensions();
        double alphasSum = sumAlphas();
        double[] m = new double[dimensions];
        for (int i = 0; i < dimensions; i++) m[i] = alphas[i] / alphasSum;
        return m;
    }

    /**
     * Component-wise standard deviation: sqrt(Var[Xᵢ]).
     * Var[Xᵢ] = αᵢ (α₀ - αᵢ) / (α₀² (α₀ + 1))
     *
     * @return array of length dimensions
     */
    public double[] std() {
        int dimensions = getDimensions();
        double alphasSum = sumAlphas();
        double[] s = new double[dimensions];
        double denom = alphasSum * alphasSum * (alphasSum + 1.0);
        for (int i = 0; i < dimensions; i++) {
            double var = alphas[i] * (alphasSum - alphas[i]) / denom;
            s[i] = Math.sqrt(Math.max(0.0, var));
        }
        return s;
    }

    /**
     * MAP (mode) estimate: mode_i = (αᵢ - 1) / (α₀ - dimensions).
     * Only well-defined when all αᵢ > 1 (α₀ > dimensions).
     * If α₀ ≤ dimensions, returns the mean as a fallback.
     *
     * @return array of length dimensions, on the simplex
     */
    public double[] map() {
        int dimensions = getDimensions();
        double alphasSum = sumAlphas();
        for (double alpha : alphas) {
            if (alpha <= 1.0) {
                return mean();
            }
        }
        double denom = alphasSum - dimensions;
        double[] m = new double[dimensions];
        for (int i = 0; i < dimensions; i++) {
            m[i] = (alphas[i] - 1.0) / denom;
        }
        return m;
    }

    /**
     * Marginal CDF of dimension {@code dim}: P(Xᵢ ≤ x).
     *
     * <p>The marginal of Xᵢ is Beta(αᵢ, α₀ − αᵢ).
     * CDF is computed via the regularised incomplete Beta function
     * I_x(αᵢ, α₀ − αᵢ) using a continued-fraction approximation.</p>
     *
     * @param x   value in [0, 1]
     * @param dim dimension index (0-based), must be in [0, dimensions)
     * @return cumulative probability in [0, 1]
     */
    public double marginalCdf(double x, int dim) {
        int dimensions = getDimensions();
        double alphasSum = sumAlphas();
        if (dim < 0 || dim >= dimensions)
            throw new IllegalArgumentException("dim must be in [0, dimensions), got: " + dim);
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;
        double a = alphas[dim];
        double b = alphasSum - alphas[dim];
        return regularizedIncompleteBeta(x, a, b);
    }

    // -----------------------------------------------------------------------
    // Sampleable
    // -----------------------------------------------------------------------

    /**
     * Draw n samples from Dir(α) using the Gamma-distribution method:
     * draw Gᵢ ~ Gamma(αᵢ, 1) independently, then normalise xᵢ = Gᵢ / ΣGⱼ.
     *
     * @return double[n][dimensions], each row on the simplex
     */
    @Override
    public double[][] sample(int n) {
        int dimensions = getDimensions();
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[][] out = new double[n][dimensions];
        for (int s = 0; s < n; s++) {
            double sum = 0.0;
            for (int i = 0; i < dimensions; i++) {
                out[s][i] = sampleGamma(rng, alphas[i]);
                sum += out[s][i];
            }
            for (int i = 0; i < dimensions; i++) out[s][i] /= sum;
        }
        return out;
    }

    /**
     * Log-density at point x (length dimensions, on the simplex).
     * log p(x|α) = -logB(α) + Σ (αᵢ-1) log xᵢ
     */
    @Override
    public double logPdf(double[] x) {
        int dimensions = getDimensions();
        double alphasSum = sumAlphas();
        if (x.length != dimensions) {
            throw new IllegalArgumentException("x.length must equal dimensions=" + dimensions);
        }
        double sum = 0.0;
        double logp = -logBetaFn(alphas, alphasSum);
        for (int i = 0; i < dimensions; i++) {
            if (x[i] <= 0.0 || x[i] > 1.0) return Double.NEGATIVE_INFINITY;
            sum += x[i];
            logp += (alphas[i] - 1.0) * Math.log(x[i]);
        }
        if (Math.abs(sum - 1.0) > 1e-9) {
            return Double.NEGATIVE_INFINITY;
        }
        return logp;
    }

    // -----------------------------------------------------------------------
    // Serialisation
    // -----------------------------------------------------------------------

    public String toJSON() {
        int dimensions = getDimensions();
        StringBuilder sb = new StringBuilder();
        sb.append("{\"alphas\":[");
        for (int i = 0; i < dimensions; i++) {
            if (i > 0) sb.append(',');
            sb.append(alphas[i]);
        }
        sb.append("]}");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "DirichletValue{dimensions=" + getDimensions() + ", alphas=" + Arrays.toString(alphas) + "}";
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DirichletValue that)) return false;
        return Arrays.equals(alphas, that.alphas);
    }

    @Override
    public int hashCode() {
        return Objects.hash(Arrays.hashCode(alphas));
    }

    // -----------------------------------------------------------------------
    // Internal helpers
    // -----------------------------------------------------------------------

    /**
     * log B(α) = Σ log Γ(αᵢ) − log Γ(α₀)
     */
    private static double logBetaFn(double[] alphas, double alphasSum) {
        double s = 0.0;
        for (double a : alphas) s += logGamma(a);
        return s - logGamma(alphasSum);
    }

    private double sumAlphas() {
        double sum = 0.0;
        for (double a : alphas) sum += a;
        return sum;
    }

    /**
     * Stirling-series log-Gamma approximation, accurate for a > 0.
     * Uses Lanczos approximation with g=5, n=6.
     */
    private static double logGamma(double a) {
        if (a <= 0) return Double.POSITIVE_INFINITY;
        // Lanczos coefficients (g=607/128, n=15 from Paul Godfrey)
        double[] c = {
            0.99999999999999709182,
            57.156235665862923517,
            -59.597960355475491248,
            14.136097974741747174,
            -0.49191381609762019978,
             3.3994649984811888699e-5,
             4.6523628927048575665e-8,
            -9.8374475304879564677e-8,
             1.5808870322491248884e-7,
            -2.1026444172410612083e-7,
             2.1743961811521264320e-7,
            -1.7120013270635977259e-7,
             1.2426022985137593942e-7,
            -5.3570895005398879730e-8,
             1.3337897061020683709e-8
        };
        if (a < 0.5) {
            // Reflection formula: Γ(a)Γ(1-a) = π/sin(πa)
            return Math.log(Math.PI / Math.sin(Math.PI * a)) - logGamma(1.0 - a);
        }
        double x = a - 1.0;
        double t = x + 14.5;  // g + 0.5
        double s = c[0];
        for (int i = 1; i < c.length; i++) s += c[i] / (x + i);
        return 0.5 * Math.log(2.0 * Math.PI) + (x + 0.5) * Math.log(t) - t + Math.log(s);
    }

    /**
     * Sample from Gamma(shape, 1) using Marsaglia-Tsang method.
     */
    private static double sampleGamma(ThreadLocalRandom rng, double shape) {
        if (shape < 1.0) {
            // Boost shape and scale by U^(1/shape)
            return sampleGamma(rng, shape + 1.0) * Math.pow(rng.nextDouble(), 1.0 / shape);
        }
        double d = shape - 1.0 / 3.0;
        double c = 1.0 / Math.sqrt(9.0 * d);
        while (true) {
            double x, v;
            do {
                x = rng.nextGaussian();
                v = 1.0 + c * x;
            } while (v <= 0.0);
            v = v * v * v;
            double u = rng.nextDouble();
            double x2 = x * x;
            if (u < 1.0 - 0.0331 * (x2 * x2) ||
                Math.log(u) < 0.5 * x2 + d * (1.0 - v + Math.log(v))) {
                return d * v;
            }
        }
    }

    /**
     * Regularised incomplete Beta function I_x(a, b) via continued fractions.
     * Accurate for a, b > 0 and x ∈ [0, 1].
     */
    static double regularizedIncompleteBeta(double x, double a, double b) {
        if (x <= 0.0) return 0.0;
        if (x >= 1.0) return 1.0;
        // Use symmetry relation for better convergence
        if (x > (a + 1.0) / (a + b + 2.0)) {
            return 1.0 - regularizedIncompleteBeta(1.0 - x, b, a);
        }
        double lbeta = logGamma(a) + logGamma(b) - logGamma(a + b);
        double front = Math.exp(a * Math.log(x) + b * Math.log(1.0 - x) - lbeta) / a;
        return front * betaContinuedFraction(x, a, b);
    }

    /** Lentz's continued-fraction expansion for the regularized incomplete Beta. */
    private static double betaContinuedFraction(double x, double a, double b) {
        final int MAX_ITER = 200;
        final double TOL = 1e-12;
        double qab = a + b;
        double qap = a + 1.0;
        double qam = a - 1.0;
        double c = 1.0;
        double d = 1.0 - qab * x / qap;
        if (Math.abs(d) < 1e-300) d = 1e-300;
        d = 1.0 / d;
        double h = d;
        for (int m = 1; m <= MAX_ITER; m++) {
            int m2 = 2 * m;
            double aa = m * (b - m) * x / ((qam + m2) * (a + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < 1e-300) d = 1e-300;
            c = 1.0 + aa / c; if (Math.abs(c) < 1e-300) c = 1e-300;
            d = 1.0 / d;
            h *= d * c;
            aa = -(a + m) * (qab + m) * x / ((a + m2) * (qap + m2));
            d = 1.0 + aa * d; if (Math.abs(d) < 1e-300) d = 1e-300;
            c = 1.0 + aa / c; if (Math.abs(c) < 1e-300) c = 1e-300;
            d = 1.0 / d;
            double delta = d * c;
            h *= delta;
            if (Math.abs(delta - 1.0) < TOL) break;
        }
        return h;
    }
}
