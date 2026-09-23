package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.transformation.Convolve;
import org.apache.jena.probsparql.functions.transformation.Joint;
import org.apache.jena.probsparql.functions.transformation.LinearTransform;
import org.apache.jena.probsparql.functions.transformation.Marginal;
import org.apache.jena.probsparql.functions.transformation.Multiply;
import org.apache.jena.probsparql.functions.transformation.Scale;
import org.apache.jena.probsparql.functions.transformation.Shift;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.apache.jena.probsparql.functions.FunctionTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness of the seven transformation functions.
 *
 * <p>Each is checked against the closed-form result of the transformation it models:
 * an affine map of a Gaussian is Gaussian with known parameters, a sum of independent
 * Gaussians has the summed mean and variance, and so on. Histogram paths are checked
 * against exact cell arithmetic.</p>
 */
class TransformationCorrectnessTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    /** Mean and variance of a single-component 1-D GMM. */
    private static double meanOf(GMMValue g) {
        return g.getMeans()[0][0];
    }

    private static double varianceOf(GMMValue g) {
        double[][] cov = g.getCovariances()[0];
        return switch (g.getCovarianceType()) {
            case "full" -> cov[0][0];
            case "diag", "spherical" -> cov[0][0];
            default -> throw new IllegalStateException(g.getCovarianceType());
        };
    }

    @Nested
    @DisplayName("prob:scale")
    class ScaleFn {
        private final Scale scale = new Scale();

        @Test
        void scalingAGaussianScalesMeanAndSquaresIntoVariance() {
            // aX ~ N(a*mu, a^2*sigma^2)
            GMMValue out = asGMM(scale.exec(nv(gaussian(2.0, 4.0)), num(3.0)));
            assertEquals(6.0, meanOf(out), EXACT);
            assertEquals(36.0, varianceOf(out), EXACT);
        }

        @Test
        void negativeFactorReflectsTheMeanAndKeepsVariancePositive() {
            GMMValue out = asGMM(scale.exec(nv(gaussian(2.0, 4.0)), num(-3.0)));
            assertEquals(-6.0, meanOf(out), EXACT);
            assertEquals(36.0, varianceOf(out), EXACT);
        }

        @Test
        void scalingByOneIsTheIdentity() {
            GMMValue out = asGMM(scale.exec(nv(gaussian(1.5, 2.5)), num(1.0)));
            assertEquals(1.5, meanOf(out), EXACT);
            assertEquals(2.5, varianceOf(out), EXACT);
        }

        @Test
        void preservesMixtureWeightsAndComponentCount() {
            GMMValue out = asGMM(scale.exec(nv(mixture(0.3, 1.0, 1.0, 4.0, 2.0)), num(2.0)));
            assertEquals(2, out.getNComponents());
            double[] weights = out.getWeights();
            assertEquals(1.0, weights[0] + weights[1], EXACT);
            assertArrayEquals(new double[]{0.3, 0.7}, sorted(weights), EXACT);
        }

        @Test
        void scalesHistogramEdgesAndPreservesCellMass() {
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue out = asHistogram(scale.exec(nv(in), num(2.0)));
            assertArrayEquals(new double[]{0.0, 2.0, 4.0}, out.getBins(), EXACT);
            assertArrayEquals(new double[]{0.25, 0.75}, out.getWeights(), EXACT);
        }

        @Test
        void negativeScaleReversesHistogramEdgesAndCellOrder() {
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue out = asHistogram(scale.exec(nv(in), num(-1.0)));
            assertArrayEquals(new double[]{-2.0, -1.0, 0.0}, out.getBins(), EXACT);
            // The cell that was rightmost is now leftmost, carrying its mass with it.
            assertArrayEquals(new double[]{0.75, 0.25}, out.getWeights(), EXACT);
        }

        @Test
        void rejectsZeroScaleOnAHistogram() {
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            assertThrows(RuntimeException.class, () -> scale.exec(nv(in), num(0.0)));
        }

        private double[] sorted(double[] values) {
            double[] copy = values.clone();
            java.util.Arrays.sort(copy);
            return new double[]{copy[0], copy[1]};
        }
    }

    @Nested
    @DisplayName("prob:shift")
    class ShiftFn {
        private final Shift shift = new Shift();

        @Test
        void shiftingMovesTheMeanAndLeavesVarianceAlone() {
            // X + b ~ N(mu + b, sigma^2)
            GMMValue out = asGMM(shift.exec(nv(gaussian(2.0, 4.0)), num(5.0)));
            assertEquals(7.0, meanOf(out), EXACT);
            assertEquals(4.0, varianceOf(out), EXACT);
        }

        @Test
        void negativeOffsetMovesLeft() {
            GMMValue out = asGMM(shift.exec(nv(gaussian(2.0, 4.0)), num(-6.5)));
            assertEquals(-4.5, meanOf(out), EXACT);
            assertEquals(4.0, varianceOf(out), EXACT);
        }

        @Test
        void shiftsEveryComponentOfAMixture() {
            GMMValue out = asGMM(shift.exec(nv(mixture(0.5, 0.0, 1.0, 10.0, 1.0)), num(3.0)));
            double[] means = {out.getMeans()[0][0], out.getMeans()[1][0]};
            java.util.Arrays.sort(means);
            assertArrayEquals(new double[]{3.0, 13.0}, means, EXACT);
        }

        @Test
        void translatesHistogramEdgesAndKeepsMass() {
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue out = asHistogram(shift.exec(nv(in), num(10.0)));
            assertArrayEquals(new double[]{10.0, 11.0, 12.0}, out.getBins(), EXACT);
            assertArrayEquals(new double[]{0.25, 0.75}, out.getWeights(), EXACT);
        }
    }

    @Nested
    @DisplayName("prob:linearTransform")
    class LinearTransformFn {
        private final LinearTransform linear = new LinearTransform();

        @Test
        void appliesAxPlusB() {
            // aX + b ~ N(a*mu + b, a^2*sigma^2)
            GMMValue out = asGMM(linear.exec(nv(gaussian(3.0, 1.0)), num(2.0), num(1.0)));
            assertEquals(7.0, meanOf(out), EXACT);
            assertEquals(4.0, varianceOf(out), EXACT);
        }

        @Test
        void matchesScaleThenShift() {
            GMMValue viaLinear = asGMM(linear.exec(nv(gaussian(1.5, 2.0)), num(-2.0), num(4.0)));
            GMMValue viaScale = asGMM(new Scale().exec(nv(gaussian(1.5, 2.0)), num(-2.0)));
            GMMValue viaBoth = asGMM(new Shift().exec(nv(viaScale), num(4.0)));
            assertEquals(meanOf(viaBoth), meanOf(viaLinear), EXACT);
            assertEquals(varianceOf(viaBoth), varianceOf(viaLinear), EXACT);
        }

        @Test
        void identityTransformLeavesTheDistributionUnchanged() {
            GMMValue out = asGMM(linear.exec(nv(gaussian(2.5, 3.5)), num(1.0), num(0.0)));
            assertEquals(2.5, meanOf(out), EXACT);
            assertEquals(3.5, varianceOf(out), EXACT);
        }

        @Test
        void appliesTheAffineMapToHistogramEdges() {
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue out = asHistogram(linear.exec(nv(in), num(3.0), num(1.0)));
            assertArrayEquals(new double[]{1.0, 4.0, 7.0}, out.getBins(), EXACT);
            assertArrayEquals(new double[]{0.25, 0.75}, out.getWeights(), EXACT);
        }
    }

    @Nested
    @DisplayName("prob:marginal")
    class MarginalFn {
        private final Marginal marginal = new Marginal();

        @Test
        void selectsOneAxisOfATwoDimensionalGaussian() {
            GMMValue source = gaussian2D(1.0, 2.0, 3.0, 4.0);

            GMMValue dim0 = asGMM(marginal.exec(nv(source), intNum(0)));
            assertEquals(1, dim0.getDimensions());
            assertEquals(1.0, dim0.getMeans()[0][0], EXACT);
            assertEquals(3.0, dim0.getCovariances()[0][0][0], EXACT);

            GMMValue dim1 = asGMM(marginal.exec(nv(source), intNum(1)));
            assertEquals(2.0, dim1.getMeans()[0][0], EXACT);
            assertEquals(4.0, dim1.getCovariances()[0][0][0], EXACT);
        }

        @Test
        void selectsASubsetOfAxesInTheRequestedOrder() {
            GMMValue source = new GMMValue(1, 3, "diag", new double[]{1.0},
                new double[][]{{1.0, 2.0, 3.0}}, new double[][][]{{{4.0, 5.0, 6.0}}});

            GMMValue out = asGMM(marginal.exec(nv(source), str("[2,0]")));
            assertEquals(2, out.getDimensions());
            assertArrayEquals(new double[]{3.0, 1.0}, out.getMeans()[0], EXACT);
            assertEquals(6.0, out.getCovariances()[0][0][0], EXACT);
            assertEquals(4.0, out.getCovariances()[0][1][1], EXACT);
        }

        @Test
        void dropsCrossCovarianceWithTheMarginalisedAxis() {
            // For a full covariance the submatrix is taken, so the retained axis keeps
            // its own variance regardless of its correlation with the dropped one.
            GMMValue source = new GMMValue(1, 2, "full", new double[]{1.0},
                new double[][]{{0.0, 0.0}}, new double[][][]{{{2.0, 1.0}, {1.0, 3.0}}});
            GMMValue out = asGMM(marginal.exec(nv(source), intNum(1)));
            assertEquals(3.0, out.getCovariances()[0][0][0], EXACT);
        }

        @Test
        void sumsHistogramMassOverTheDroppedAxis() {
            // 2x2 grid, masses in row-major order: (0,0)=0.1 (0,1)=0.2 (1,0)=0.3 (1,1)=0.4
            HistogramValue source = new HistogramValue(2,
                new double[][]{{0.0, 1.0, 2.0}, {0.0, 1.0, 2.0}},
                new double[]{0.1, 0.2, 0.3, 0.4});

            // Keeping axis 0 sums over axis 1: [0.1+0.2, 0.3+0.4]
            HistogramValue axis0 = asHistogram(marginal.exec(nv(source), intNum(0)));
            assertArrayEquals(new double[]{0.3, 0.7}, axis0.getWeights(), EXACT);

            // Keeping axis 1 sums over axis 0: [0.1+0.3, 0.2+0.4]
            HistogramValue axis1 = asHistogram(marginal.exec(nv(source), intNum(1)));
            assertArrayEquals(new double[]{0.4, 0.6}, axis1.getWeights(), EXACT);
        }

        @Test
        void marginalMassAlwaysSumsToOne() {
            HistogramValue source = new HistogramValue(2,
                new double[][]{{0.0, 1.0, 2.0}, {0.0, 1.0, 2.0}},
                new double[]{0.1, 0.2, 0.3, 0.4});
            for (int axis = 0; axis < 2; axis++) {
                double sum = 0.0;
                for (double w : asHistogram(marginal.exec(nv(source), intNum(axis))).getWeights()) {
                    sum += w;
                }
                assertEquals(1.0, sum, EXACT, "axis " + axis);
            }
        }

        @Test
        void rejectsANonIntegerAxisIndex() {
            // Dimension indices select an axis, so a fractional index has no meaning.
            assertThrows(RuntimeException.class,
                () -> marginal.exec(nv(gaussian2D(0.0, 0.0, 1.0, 1.0)), num(0.5)));
        }

        @Test
        void rejectsOutOfRangeAndDuplicateAxes() {
            GMMValue source = gaussian2D(0.0, 0.0, 1.0, 1.0);
            assertThrows(RuntimeException.class, () -> marginal.exec(nv(source), intNum(2)));
            assertThrows(RuntimeException.class, () -> marginal.exec(nv(source), intNum(-1)));
            assertThrows(RuntimeException.class, () -> marginal.exec(nv(source), str("[0,0]")));
        }
    }

    @Nested
    @DisplayName("prob:joint")
    class JointFn {
        private final Joint joint = new Joint();

        @Test
        void stacksTwoIndependentGaussiansIntoOneTwoDimensional() {
            GMMValue out = asGMM(joint.exec(nv(gaussian(1.0, 1.0)), nv(gaussian(5.0, 4.0))));
            assertEquals(2, out.getDimensions());
            assertArrayEquals(new double[]{1.0, 5.0}, out.getMeans()[0], EXACT);
            assertEquals(1.0, out.getCovariances()[0][0][0], EXACT);
            assertEquals(4.0, out.getCovariances()[0][1][1], EXACT);
        }

        @Test
        void independentAxesHaveZeroCrossCovariance() {
            GMMValue out = asGMM(joint.exec(nv(gaussian(0.0, 2.0)), nv(gaussian(0.0, 3.0))));
            assertEquals(0.0, out.getCovariances()[0][0][1], EXACT);
            assertEquals(0.0, out.getCovariances()[0][1][0], EXACT);
        }

        @Test
        void marginalisingTheJointRecoversEachOperand() {
            GMMValue left = gaussian(2.0, 1.5);
            GMMValue right = gaussian(-3.0, 0.5);
            GMMValue combined = asGMM(joint.exec(nv(left), nv(right)));

            GMMValue back0 = asGMM(new Marginal().exec(nv(combined), intNum(0)));
            GMMValue back1 = asGMM(new Marginal().exec(nv(combined), intNum(1)));
            assertEquals(2.0, back0.getMeans()[0][0], EXACT);
            assertEquals(1.5, back0.getCovariances()[0][0][0], EXACT);
            assertEquals(-3.0, back1.getMeans()[0][0], EXACT);
            assertEquals(0.5, back1.getCovariances()[0][0][0], EXACT);
        }

        @Test
        void histogramJointIsTheOuterProductOfCellMasses() {
            HistogramValue left = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue right = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue out = asHistogram(joint.exec(nv(left), nv(right)));

            assertEquals(2, out.getDimensions());
            // Row-major: (0,0)=0.25*0.5, (0,1)=0.25*0.5, (1,0)=0.75*0.5, (1,1)=0.75*0.5
            assertArrayEquals(new double[]{0.125, 0.125, 0.375, 0.375}, out.getWeights(), EXACT);
        }
    }

    @Nested
    @DisplayName("prob:convolve")
    class ConvolveFn {
        private final Convolve convolve = new Convolve();

        @Test
        void sumOfIndependentGaussiansAddsMeansAndVariances() {
            // X + Y ~ N(mu1 + mu2, sigma1^2 + sigma2^2)
            GMMValue out = asGMM(convolve.exec(nv(gaussian(1.0, 2.0)), nv(gaussian(3.0, 5.0))));
            assertEquals(4.0, meanOf(out), EXACT);
            assertEquals(7.0, varianceOf(out), EXACT);
        }

        @Test
        void isCommutative() {
            GMMValue forward = asGMM(convolve.exec(nv(gaussian(1.0, 2.0)), nv(gaussian(-4.0, 0.5))));
            GMMValue reverse = asGMM(convolve.exec(nv(gaussian(-4.0, 0.5)), nv(gaussian(1.0, 2.0))));
            assertEquals(meanOf(forward), meanOf(reverse), EXACT);
            assertEquals(varianceOf(forward), varianceOf(reverse), EXACT);
        }

        @Test
        void histogramConvolutionMatchesDiscreteSelfConvolution() {
            // Two identical two-cell histograms with masses [0.25, 0.75] on unit bins.
            // The discrete convolution is [0.0625, 0.375, 0.5625] over three unit cells
            // starting at 0 + 0 = 0.
            HistogramValue in = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue out = asHistogram(convolve.exec(nv(in), nv(in)));
            assertArrayEquals(new double[]{0.0625, 0.375, 0.5625}, out.getWeights(), 1e-12);
            assertArrayEquals(new double[]{0.0, 1.0, 2.0, 3.0}, out.getBins(), EXACT);
        }

        @Test
        void histogramConvolutionConservesTotalMass() {
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.2, 0.3, 0.5});
            HistogramValue b = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.6, 0.4});
            double sum = 0.0;
            for (double w : asHistogram(convolve.exec(nv(a), nv(b))).getWeights()) {
                sum += w;
            }
            assertEquals(1.0, sum, 1e-12);
        }

        @Test
        void rejectsUnequalHistogramBinWidths() {
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue b = histogram(new double[]{0.0, 2.0, 4.0}, new double[]{0.5, 0.5});
            assertThrows(RuntimeException.class, () -> convolve.exec(nv(a), nv(b)));
        }

        @Test
        void rejectsMismatchedDimensionality() {
            assertThrows(RuntimeException.class,
                () -> convolve.exec(nv(gaussian(0.0, 1.0)), nv(gaussian2D(0.0, 0.0, 1.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:multiply")
    class MultiplyFn {
        private final Multiply multiply = new Multiply();

        @Test
        void momentMatchesTheProductOfTwoIndependentVariables() {
            // For independent X, Y:  E[XY] = E[X]E[Y]
            //                        Var[XY] = mu_x^2*var_y + mu_y^2*var_x + var_x*var_y
            GMMValue out = asGMM(multiply.exec(nv(gaussian(2.0, 1.0)), nv(gaussian(3.0, 4.0))));
            assertEquals(6.0, meanOf(out), EXACT);
            assertEquals(2.0 * 2.0 * 4.0 + 3.0 * 3.0 * 1.0 + 1.0 * 4.0, varianceOf(out), EXACT);
        }

        @Test
        void multiplyingByADegenerateConstantScalesTheOperand() {
            // Y with negligible variance behaves like the constant mu_y, so
            // Var[XY] -> mu_y^2 * Var[X].
            GMMValue out = asGMM(multiply.exec(nv(gaussian(5.0, 2.0)), nv(gaussian(3.0, 1e-12))));
            assertEquals(15.0, meanOf(out), 1e-9);
            assertEquals(9.0 * 2.0, varianceOf(out), 1e-6);
        }

        @Test
        void isCommutative() {
            GMMValue forward = asGMM(multiply.exec(nv(gaussian(2.0, 1.0)), nv(gaussian(3.0, 4.0))));
            GMMValue reverse = asGMM(multiply.exec(nv(gaussian(3.0, 4.0)), nv(gaussian(2.0, 1.0))));
            assertEquals(meanOf(forward), meanOf(reverse), EXACT);
            assertEquals(varianceOf(forward), varianceOf(reverse), EXACT);
        }

        @Test
        void histogramMultiplyIsPointwiseCellProductRenormalised() {
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            HistogramValue b = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            // Raw products 0.125 and 0.375 sum to 0.5, renormalising to 0.25 and 0.75.
            HistogramValue out = asHistogram(multiply.exec(nv(a), nv(b)));
            assertArrayEquals(new double[]{0.25, 0.75}, out.getWeights(), 1e-12);
        }

        @Test
        void rejectsHistogramsOnDifferentGrids() {
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue b = histogram(new double[]{0.0, 3.0, 6.0}, new double[]{0.5, 0.5});
            assertThrows(RuntimeException.class, () -> multiply.exec(nv(a), nv(b)));
        }

        @Test
        void rejectsHistogramPairWithDisjointSupport() {
            // Every cell product is zero, so there is no distribution to return.
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{1.0, 0.0});
            HistogramValue b = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.0, 1.0});
            RuntimeException error = assertThrows(RuntimeException.class,
                () -> multiply.exec(nv(a), nv(b)));
            assertTrue(error.getMessage().toLowerCase().contains("mass"),
                "error should explain that the product has no mass, got: " + error.getMessage());
        }
    }
}
