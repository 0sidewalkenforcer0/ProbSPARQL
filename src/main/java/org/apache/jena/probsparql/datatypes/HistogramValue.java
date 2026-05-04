package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Represents a multidimensional histogram distribution on an axis-aligned grid.
 *
 * <p>The preferred lexical form is:</p>
 * <pre>
 * {"dimensions":2,"edges":[[0.0,1.0,2.0],[10.0,20.0,30.0]],"weights":[0.1,0.2,0.3,0.4]}
 * </pre>
 *
 * <p>Each {@code edges[i]} entry contains the strictly increasing boundaries
 * for dimension {@code i}. The Cartesian product of all per-dimension bins
 * defines the histogram cells. {@code weights} stores the probability mass of
 * each cell in row-major order (last dimension changes fastest).</p>
 */
public class HistogramValue implements Sampleable {

    private final int dimensions;
    private final double[][] edges;
    private final double[] weights;
    private final int[] binCounts;
    private final int[] strides;

    /**
     * Backwards-compatible convenience constructor for 1-D histograms.
     */
    public HistogramValue(double[] bins, double[] weights) {
        this(1, new double[][]{bins}, weights);
    }

    public HistogramValue(int dimensions, double[][] edges, double[] weights) {
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (edges == null || edges.length != dimensions) {
            throw new IllegalArgumentException("edges must have length dimensions=" + dimensions);
        }
        if (weights == null || weights.length == 0) {
            throw new IllegalArgumentException("weights must be non-empty");
        }

        this.dimensions = dimensions;
        this.edges = deepCopy2D(edges);
        this.binCounts = new int[dimensions];
        this.strides = new int[dimensions];

        long cellCount = 1L;
        for (int dim = dimensions - 1; dim >= 0; dim--) {
            double[] dimEdges = this.edges[dim];
            if (dimEdges == null || dimEdges.length < 2) {
                throw new IllegalArgumentException("edges[" + dim + "] must have length at least 2");
            }
            double prev = dimEdges[0];
            for (int i = 1; i < dimEdges.length; i++) {
                if (!(dimEdges[i] > prev)) {
                    throw new IllegalArgumentException("edges[" + dim + "] must be strictly increasing");
                }
                prev = dimEdges[i];
            }

            binCounts[dim] = dimEdges.length - 1;
            strides[dim] = (int) cellCount;
            cellCount *= binCounts[dim];
            if (cellCount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Histogram cell count exceeds supported range: " + cellCount);
            }
        }

        if (weights.length != (int) cellCount) {
            throw new IllegalArgumentException(
                "weights must have length " + cellCount + ", got: " + weights.length);
        }

        double sum = 0.0;
        for (double weight : weights) {
            if (weight < 0.0) {
                throw new IllegalArgumentException("weights must be non-negative");
            }
            sum += weight;
        }
        if (Math.abs(sum - 1.0) > 1e-6) {
            throw new IllegalArgumentException("weights must sum to 1.0, got: " + sum);
        }

        this.weights = weights.clone();
    }

    public int getDimensions() {
        return dimensions;
    }

    /**
     * Returns the 1-D bin boundaries.
     *
     * @throws IllegalStateException if called on a multidimensional histogram
     */
    public double[] getBins() {
        ensureOneDimensional("getBins");
        return edges[0].clone();
    }

    public double[][] getEdges() {
        return deepCopy2D(edges);
    }

    public double[] getWeights() {
        return weights.clone();
    }

    /**
     * Returns the total number of grid cells across all dimensions.
     */
    public int getBinCount() {
        return weights.length;
    }

    public int[] getBinCounts() {
        return binCounts.clone();
    }

    /**
     * Legacy 1-D alias retained for older callers.
     */
    public double[] edges() {
        return getBins();
    }

    /**
     * Legacy 1-D helper retained for existing code paths.
     */
    public double[] binCenters() {
        ensureOneDimensional("binCenters");
        return dimensionCenters(0);
    }

    public double[][] cellCenters() {
        double[][] centers = new double[getBinCount()][dimensions];
        int[] cellIndex = new int[dimensions];
        for (int flat = 0; flat < getBinCount(); flat++) {
            unflattenIndex(flat, cellIndex);
            for (int dim = 0; dim < dimensions; dim++) {
                double[] dimEdges = edges[dim];
                int idx = cellIndex[dim];
                centers[flat][dim] = 0.5 * (dimEdges[idx] + dimEdges[idx + 1]);
            }
        }
        return centers;
    }

    public double[] probabilities() {
        return getWeights();
    }

    /**
     * 1-D CDF retained for backwards compatibility.
     */
    public double cdf(double x) {
        ensureOneDimensional("cdf(double)");
        return cdf(new double[]{x});
    }

    /**
     * Joint CDF P(X1 <= x1, ..., Xd <= xd) under the piecewise-constant
     * density implied by the histogram cells.
     */
    public double cdf(double[] point) {
        validatePoint(point);
        double cumulative = 0.0;
        int[] cellIndex = new int[dimensions];

        for (int flat = 0; flat < getBinCount(); flat++) {
            unflattenIndex(flat, cellIndex);
            double proportion = overlapProportion(cellIndex, point);
            if (proportion > 0.0) {
                cumulative += weights[flat] * proportion;
            }
        }

        return Math.max(0.0, Math.min(cumulative, 1.0));
    }

    /**
     * 1-D mean retained for backwards compatibility.
     */
    public double mean() {
        ensureOneDimensional("mean");
        return meanVector()[0];
    }

    public double[] meanVector() {
        double[] mean = new double[dimensions];
        int[] cellIndex = new int[dimensions];

        for (int flat = 0; flat < getBinCount(); flat++) {
            unflattenIndex(flat, cellIndex);
            for (int dim = 0; dim < dimensions; dim++) {
                double[] dimEdges = edges[dim];
                int idx = cellIndex[dim];
                double center = 0.5 * (dimEdges[idx] + dimEdges[idx + 1]);
                mean[dim] += weights[flat] * center;
            }
        }
        return mean;
    }

    public double[] stdVector() {
        double[] mean = meanVector();
        double[] variance = new double[dimensions];
        int[] cellIndex = new int[dimensions];

        for (int flat = 0; flat < getBinCount(); flat++) {
            unflattenIndex(flat, cellIndex);
            for (int dim = 0; dim < dimensions; dim++) {
                double[] dimEdges = edges[dim];
                int idx = cellIndex[dim];
                double lo = dimEdges[idx];
                double hi = dimEdges[idx + 1];
                double width = hi - lo;
                double center = 0.5 * (lo + hi);
                double delta = center - mean[dim];
                double withinCellVar = (width * width) / 12.0;
                variance[dim] += weights[flat] * (delta * delta + withinCellVar);
            }
        }

        double[] std = new double[dimensions];
        for (int dim = 0; dim < dimensions; dim++) {
            std[dim] = Math.sqrt(Math.max(0.0, variance[dim]));
        }
        return std;
    }

    public double[] mapPoint() {
        int maxFlat = 0;
        for (int flat = 1; flat < getBinCount(); flat++) {
            if (weights[flat] > weights[maxFlat]) {
                maxFlat = flat;
            }
        }

        int[] cellIndex = new int[dimensions];
        unflattenIndex(maxFlat, cellIndex);
        double[] point = new double[dimensions];
        for (int dim = 0; dim < dimensions; dim++) {
            double[] dimEdges = edges[dim];
            int idx = cellIndex[dim];
            point[dim] = 0.5 * (dimEdges[idx] + dimEdges[idx + 1]);
        }
        return point;
    }

    /**
     * Structural compatibility with another histogram: same dimensionality and
     * same grid boundaries within a scale-aware floating-point tolerance.
     */
    public boolean isCompatible(HistogramValue other) {
        if (other == null || this.dimensions != other.dimensions) {
            return false;
        }
        for (int dim = 0; dim < dimensions; dim++) {
            if (this.binCounts[dim] != other.binCounts[dim]) {
                return false;
            }
            double[] leftEdges = this.edges[dim];
            double[] rightEdges = other.edges[dim];
            double scale = Math.max(1.0, Math.abs(leftEdges[leftEdges.length - 1] - leftEdges[0]));
            double eps = 1e-9 * scale;
            for (int i = 0; i < leftEdges.length; i++) {
                if (Math.abs(leftEdges[i] - rightEdges[i]) > eps) {
                    return false;
                }
            }
        }
        return true;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HistogramValue that)) return false;
        return dimensions == that.dimensions
            && Arrays.deepEquals(edges, that.edges)
            && Arrays.equals(weights, that.weights);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dimensions, Arrays.deepHashCode(edges), Arrays.hashCode(weights));
    }

    @Override
    public double[][] sample(int n) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[][] samples = new double[n][dimensions];
        int[] cellIndex = new int[dimensions];

        for (int sampleIdx = 0; sampleIdx < n; sampleIdx++) {
            int flat = sampleCell(rng);
            unflattenIndex(flat, cellIndex);
            for (int dim = 0; dim < dimensions; dim++) {
                double[] dimEdges = edges[dim];
                int idx = cellIndex[dim];
                double lo = dimEdges[idx];
                double hi = dimEdges[idx + 1];
                samples[sampleIdx][dim] = lo + rng.nextDouble() * (hi - lo);
            }
        }

        return samples;
    }

    @Override
    public double logPdf(double[] x) {
        validatePoint(x);
        int[] cellIndex = locateCell(x);
        if (cellIndex == null) {
            return Double.NEGATIVE_INFINITY;
        }

        int flat = flattenIndex(cellIndex);
        double mass = weights[flat];
        if (mass <= 0.0) {
            return Double.NEGATIVE_INFINITY;
        }

        double volume = 1.0;
        for (int dim = 0; dim < dimensions; dim++) {
            double[] dimEdges = edges[dim];
            int idx = cellIndex[dim];
            volume *= (dimEdges[idx + 1] - dimEdges[idx]);
        }
        return Math.log(mass / volume);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"dimensions\":").append(dimensions).append(",\"edges\":[");
        for (int dim = 0; dim < dimensions; dim++) {
            if (dim > 0) sb.append(',');
            sb.append(arrayToJson(edges[dim]));
        }
        sb.append("],\"weights\":").append(arrayToJson(weights)).append('}');
        return sb.toString();
    }

    private String arrayToJson(double[] values) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private int sampleCell(ThreadLocalRandom rng) {
        double u = rng.nextDouble();
        double cumulative = 0.0;
        int flat = getBinCount() - 1;
        for (int i = 0; i < getBinCount(); i++) {
            cumulative += weights[i];
            if (u <= cumulative) {
                flat = i;
                break;
            }
        }
        return flat;
    }

    private int[] locateCell(double[] point) {
        int[] cellIndex = new int[dimensions];
        for (int dim = 0; dim < dimensions; dim++) {
            double v = point[dim];
            double[] dimEdges = edges[dim];
            if (v < dimEdges[0] || v >= dimEdges[dimEdges.length - 1]) {
                if (!(v == dimEdges[dimEdges.length - 1] && dimensions == 1)) {
                    return null;
                }
            }

            int idx = -1;
            for (int i = 0; i < dimEdges.length - 1; i++) {
                double lo = dimEdges[i];
                double hi = dimEdges[i + 1];
                if ((v >= lo && v < hi) || (v == dimEdges[dimEdges.length - 1] && i == dimEdges.length - 2)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return null;
            }
            cellIndex[dim] = idx;
        }
        return cellIndex;
    }

    private double overlapProportion(int[] cellIndex, double[] point) {
        double proportion = 1.0;
        for (int dim = 0; dim < dimensions; dim++) {
            double[] dimEdges = edges[dim];
            int idx = cellIndex[dim];
            double lo = dimEdges[idx];
            double hi = dimEdges[idx + 1];
            double x = point[dim];

            if (x <= lo) {
                return 0.0;
            }
            if (x >= hi) {
                continue;
            }

            proportion *= (x - lo) / (hi - lo);
            if (proportion == 0.0) {
                return 0.0;
            }
        }
        return proportion;
    }

    private int flattenIndex(int[] index) {
        int flat = 0;
        for (int dim = 0; dim < dimensions; dim++) {
            flat += index[dim] * strides[dim];
        }
        return flat;
    }

    private void unflattenIndex(int flat, int[] out) {
        int remainder = flat;
        for (int dim = 0; dim < dimensions; dim++) {
            out[dim] = (remainder / strides[dim]) % binCounts[dim];
            remainder %= strides[dim] * binCounts[dim];
        }
    }

    private double[] dimensionCenters(int dim) {
        double[] centers = new double[binCounts[dim]];
        for (int i = 0; i < centers.length; i++) {
            centers[i] = 0.5 * (edges[dim][i] + edges[dim][i + 1]);
        }
        return centers;
    }

    private void validatePoint(double[] point) {
        if (point == null || point.length != dimensions) {
            throw new IllegalArgumentException(
                "Point dimension mismatch: expected " + dimensions + ", got "
                    + (point == null ? "null" : point.length));
        }
    }

    private void ensureOneDimensional(String method) {
        if (dimensions != 1) {
            throw new IllegalStateException(method + " is only valid for 1-D histograms");
        }
    }

    private static double[][] deepCopy2D(double[][] original) {
        double[][] copy = new double[original.length][];
        for (int i = 0; i < original.length; i++) {
            copy[i] = original[i].clone();
        }
        return copy;
    }
}
