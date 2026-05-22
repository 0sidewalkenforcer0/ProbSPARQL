package org.apache.jena.probsparql.datatypes;

import java.util.Arrays;

/**
 * Utility operations for multidimensional histogram distributions.
 */
public final class HistogramOperations {

    private HistogramOperations() {
    }

    public static HistogramValue affine(HistogramValue histogram, double scale, double offset) {
        if (scale == 0.0 || !Double.isFinite(scale) || !Double.isFinite(offset)) {
            throw new IllegalArgumentException("Histogram affine transform requires finite non-zero scale");
        }

        int dimensions = histogram.getDimensions();
        double[][] oldEdges = histogram.getEdges();
        double[][] newEdges = new double[dimensions][];
        boolean[] reversed = new boolean[dimensions];

        for (int dim = 0; dim < dimensions; dim++) {
            double[] transformed = new double[oldEdges[dim].length];
            for (int i = 0; i < transformed.length; i++) {
                transformed[i] = scale * oldEdges[dim][i] + offset;
            }
            if (scale < 0.0) {
                reverseInPlace(transformed);
                reversed[dim] = true;
            }
            newEdges[dim] = transformed;
        }

        return new HistogramValue(dimensions, newEdges, reorderWeights(histogram, reversed));
    }

    public static HistogramValue marginal(HistogramValue histogram, int dimension) {
        return marginal(histogram, new int[]{dimension});
    }

    public static HistogramValue marginal(HistogramValue histogram, int[] selectedDimensions) {
        int dimensions = histogram.getDimensions();
        validateSelectedDimensions(selectedDimensions, dimensions, "Histogram marginal");

        if (selectedDimensions.length == dimensions) {
            double[][] edges = new double[dimensions][];
            double[][] sourceEdges = histogram.getEdges();
            for (int i = 0; i < selectedDimensions.length; i++) {
                edges[i] = sourceEdges[selectedDimensions[i]];
            }
            if (isIdentitySelection(selectedDimensions)) {
                return histogram;
            }
            return reorderDimensions(histogram, selectedDimensions, edges);
        }

        int[] sourceBinCounts = histogram.getBinCounts();
        int[] outBinCounts = new int[selectedDimensions.length];
        double[][] sourceEdges = histogram.getEdges();
        double[][] outEdges = new double[selectedDimensions.length][];
        for (int i = 0; i < selectedDimensions.length; i++) {
            int dim = selectedDimensions[i];
            outBinCounts[i] = sourceBinCounts[dim];
            outEdges[i] = sourceEdges[dim];
        }

        double[] out = new double[cellCount(outBinCounts)];
        int[] sourceIndex = new int[dimensions];
        int[] outIndex = new int[selectedDimensions.length];
        double[] weights = histogram.getWeights();
        for (int flat = 0; flat < weights.length; flat++) {
            unflatten(flat, sourceBinCounts, sourceIndex);
            for (int i = 0; i < selectedDimensions.length; i++) {
                outIndex[i] = sourceIndex[selectedDimensions[i]];
            }
            out[flatten(outIndex, outBinCounts)] += weights[flat];
        }
        return new HistogramValue(selectedDimensions.length, outEdges, out);
    }

    public static HistogramValue independentJoint(HistogramValue left, HistogramValue right) {
        int leftDims = left.getDimensions();
        int rightDims = right.getDimensions();
        double[][] leftEdges = left.getEdges();
        double[][] rightEdges = right.getEdges();
        double[][] edges = new double[leftDims + rightDims][];
        for (int i = 0; i < leftDims; i++) {
            edges[i] = leftEdges[i];
        }
        for (int i = 0; i < rightDims; i++) {
            edges[leftDims + i] = rightEdges[i];
        }

        double[] leftWeights = left.getWeights();
        double[] rightWeights = right.getWeights();
        double[] weights = new double[leftWeights.length * rightWeights.length];
        int out = 0;
        for (double leftWeight : leftWeights) {
            for (double rightWeight : rightWeights) {
                weights[out++] = leftWeight * rightWeight;
            }
        }
        return new HistogramValue(edges.length, edges, weights);
    }

    public static HistogramValue mix(HistogramValue left, HistogramValue right, double alpha) {
        requireCompatible(left, right, "mix");
        double[] leftWeights = left.getWeights();
        double[] rightWeights = right.getWeights();
        double[] weights = new double[leftWeights.length];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = alpha * leftWeights[i] + (1.0 - alpha) * rightWeights[i];
        }
        return new HistogramValue(left.getDimensions(), left.getEdges(), normalize(weights));
    }

    public static HistogramValue multiply(HistogramValue left, HistogramValue right) {
        requireCompatible(left, right, "multiply");
        double[] leftWeights = left.getWeights();
        double[] rightWeights = right.getWeights();
        double[] weights = new double[leftWeights.length];
        for (int i = 0; i < weights.length; i++) {
            weights[i] = leftWeights[i] * rightWeights[i];
        }
        return new HistogramValue(left.getDimensions(), left.getEdges(), normalize(weights));
    }

    public static HistogramValue convolve1D(HistogramValue left, HistogramValue right) {
        if (left.getDimensions() != 1 || right.getDimensions() != 1) {
            throw new IllegalArgumentException("Histogram convolution currently supports 1-D histograms only");
        }
        double[] leftEdges = left.getBins();
        double[] rightEdges = right.getBins();
        double leftWidth = uniformWidth(leftEdges, "left");
        double rightWidth = uniformWidth(rightEdges, "right");
        double width = Math.max(leftWidth, rightWidth);
        if (Math.abs(leftWidth - rightWidth) > 1e-9 * width) {
            throw new IllegalArgumentException("Histogram convolution requires equal-width bins");
        }

        double[] leftWeights = left.getWeights();
        double[] rightWeights = right.getWeights();
        double[] weights = new double[leftWeights.length + rightWeights.length - 1];
        for (int i = 0; i < leftWeights.length; i++) {
            for (int j = 0; j < rightWeights.length; j++) {
                weights[i + j] += leftWeights[i] * rightWeights[j];
            }
        }

        double start = leftEdges[0] + rightEdges[0];
        double[] edges = new double[weights.length + 1];
        for (int i = 0; i < edges.length; i++) {
            edges[i] = start + i * width;
        }
        return new HistogramValue(edges, normalize(weights));
    }

    public static double klDivergence(HistogramValue p, HistogramValue q) {
        requireCompatible(p, q, "KL divergence");
        double[] pWeights = p.getWeights();
        double[] qWeights = q.getWeights();
        double kl = 0.0;
        for (int i = 0; i < pWeights.length; i++) {
            if (pWeights[i] == 0.0) {
                continue;
            }
            if (qWeights[i] == 0.0) {
                return Double.POSITIVE_INFINITY;
            }
            kl += pWeights[i] * Math.log(pWeights[i] / qWeights[i]);
        }
        return Math.max(0.0, kl);
    }

    public static double quantile(HistogramValue histogram, double q) {
        if (histogram.getDimensions() != 1) {
            throw new IllegalArgumentException("Histogram quantile only supports 1-D histograms");
        }
        if (q < 0.0 || q > 1.0) {
            throw new IllegalArgumentException("Quantile probability must be in [0,1], got: " + q);
        }
        double[] edges = histogram.getBins();
        if (q == 0.0) {
            return edges[0];
        }
        if (q == 1.0) {
            return edges[edges.length - 1];
        }

        double cumulative = 0.0;
        double[] weights = histogram.getWeights();
        for (int i = 0; i < weights.length; i++) {
            double next = cumulative + weights[i];
            if (q <= next) {
                if (weights[i] == 0.0) {
                    return edges[i];
                }
                double fraction = (q - cumulative) / weights[i];
                return edges[i] + fraction * (edges[i + 1] - edges[i]);
            }
            cumulative = next;
        }
        return edges[edges.length - 1];
    }

    public static void requireCompatible(HistogramValue left, HistogramValue right, String operation) {
        if (!left.isCompatible(right)) {
            throw new IllegalArgumentException(
                "Histogram " + operation + " requires identical dimensional grids. "
                    + "left=" + Arrays.deepToString(left.getEdges())
                    + " right=" + Arrays.deepToString(right.getEdges()));
        }
    }

    private static HistogramValue reorderDimensions(HistogramValue histogram, int[] selectedDimensions, double[][] edges) {
        int dimensions = histogram.getDimensions();
        int[] sourceBinCounts = histogram.getBinCounts();
        int[] outBinCounts = new int[dimensions];
        for (int i = 0; i < selectedDimensions.length; i++) {
            outBinCounts[i] = sourceBinCounts[selectedDimensions[i]];
        }

        double[] out = new double[histogram.getWeights().length];
        int[] sourceIndex = new int[dimensions];
        int[] outIndex = new int[dimensions];
        double[] weights = histogram.getWeights();
        for (int flat = 0; flat < weights.length; flat++) {
            unflatten(flat, sourceBinCounts, sourceIndex);
            for (int i = 0; i < selectedDimensions.length; i++) {
                outIndex[i] = sourceIndex[selectedDimensions[i]];
            }
            out[flatten(outIndex, outBinCounts)] = weights[flat];
        }
        return new HistogramValue(dimensions, edges, out);
    }

    private static void validateSelectedDimensions(int[] selectedDimensions, int dimensions, String operation) {
        if (selectedDimensions == null || selectedDimensions.length == 0) {
            throw new IllegalArgumentException(operation + " requires at least one selected dimension");
        }
        boolean[] seen = new boolean[dimensions];
        for (int dim : selectedDimensions) {
            if (dim < 0 || dim >= dimensions) {
                throw new IllegalArgumentException(
                    operation + " dimension out of range: " + dim + " for dimensions=" + dimensions);
            }
            if (seen[dim]) {
                throw new IllegalArgumentException(operation + " dimension appears more than once: " + dim);
            }
            seen[dim] = true;
        }
    }

    private static boolean isIdentitySelection(int[] selectedDimensions) {
        for (int i = 0; i < selectedDimensions.length; i++) {
            if (selectedDimensions[i] != i) {
                return false;
            }
        }
        return true;
    }

    private static double[] reorderWeights(HistogramValue histogram, boolean[] reversed) {
        int[] binCounts = histogram.getBinCounts();
        double[] oldWeights = histogram.getWeights();
        double[] newWeights = new double[oldWeights.length];
        int[] oldIndex = new int[binCounts.length];
        int[] newIndex = new int[binCounts.length];

        for (int oldFlat = 0; oldFlat < oldWeights.length; oldFlat++) {
            unflatten(oldFlat, binCounts, oldIndex);
            for (int dim = 0; dim < binCounts.length; dim++) {
                newIndex[dim] = reversed[dim] ? binCounts[dim] - 1 - oldIndex[dim] : oldIndex[dim];
            }
            newWeights[flatten(newIndex, binCounts)] = oldWeights[oldFlat];
        }
        return newWeights;
    }

    private static double[] normalize(double[] weights) {
        double sum = 0.0;
        for (double weight : weights) {
            if (weight < 0.0 || !Double.isFinite(weight)) {
                throw new IllegalArgumentException("Histogram weights must be finite and non-negative");
            }
            sum += weight;
        }
        if (sum <= 0.0) {
            throw new IllegalArgumentException("Histogram operation produced zero total mass");
        }
        double[] normalized = new double[weights.length];
        for (int i = 0; i < weights.length; i++) {
            normalized[i] = weights[i] / sum;
        }
        return normalized;
    }

    private static double uniformWidth(double[] edges, String label) {
        double width = edges[1] - edges[0];
        for (int i = 1; i < edges.length - 1; i++) {
            double current = edges[i + 1] - edges[i];
            if (Math.abs(current - width) > 1e-9 * Math.max(1.0, Math.abs(width))) {
                throw new IllegalArgumentException(label + " histogram must have uniform bin width");
            }
        }
        return width;
    }

    private static void reverseInPlace(double[] values) {
        for (int i = 0, j = values.length - 1; i < j; i++, j--) {
            double tmp = values[i];
            values[i] = values[j];
            values[j] = tmp;
        }
    }

    private static int flatten(int[] index, int[] binCounts) {
        int flat = 0;
        int stride = 1;
        for (int dim = binCounts.length - 1; dim >= 0; dim--) {
            flat += index[dim] * stride;
            stride *= binCounts[dim];
        }
        return flat;
    }

    private static int cellCount(int[] binCounts) {
        int count = 1;
        for (int binCount : binCounts) {
            count *= binCount;
        }
        return count;
    }

    private static void unflatten(int flat, int[] binCounts, int[] out) {
        int remainder = flat;
        int[] strides = new int[binCounts.length];
        int stride = 1;
        for (int dim = binCounts.length - 1; dim >= 0; dim--) {
            strides[dim] = stride;
            stride *= binCounts[dim];
        }
        for (int dim = 0; dim < binCounts.length; dim++) {
            out[dim] = (remainder / strides[dim]) % binCounts[dim];
            remainder %= strides[dim] * binCounts[dim];
        }
    }
}
