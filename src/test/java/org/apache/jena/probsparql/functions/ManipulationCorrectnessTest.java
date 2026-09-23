package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.manipulation.Fuse;
import org.apache.jena.probsparql.functions.manipulation.HistogramMean;
import org.apache.jena.probsparql.functions.manipulation.Map;
import org.apache.jena.probsparql.functions.manipulation.Mean;
import org.apache.jena.probsparql.functions.manipulation.Mix;
import org.apache.jena.probsparql.functions.manipulation.ModeCount;
import org.apache.jena.probsparql.functions.manipulation.Quantile;
import org.apache.jena.probsparql.functions.manipulation.Sample;
import org.apache.jena.probsparql.functions.manipulation.Std;
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
 * Correctness of the nine manipulation functions, checked against closed-form moments
 * and exact discrete sums.
 */
class ManipulationCorrectnessTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Nested
    @DisplayName("prob:mean")
    class MeanFn {
        private final Mean mean = new Mean();

        @Test
        void singleGaussianReturnsItsMean() {
            assertArrayEquals(new double[]{2.5}, asVector(mean.exec(nv(gaussian(2.5, 9.0)))), 1e-6);
        }

        @Test
        void mixtureMeanIsTheWeightedAverageOfComponentMeans() {
            // 0.3*1 + 0.7*5 = 3.8
            GMMValue g = mixture(0.3, 1.0, 1.0, 5.0, 4.0);
            assertArrayEquals(new double[]{3.8}, asVector(mean.exec(nv(g))), 1e-6);
        }

        @Test
        void returnsOneEntryPerDimension() {
            double[] v = asVector(mean.exec(nv(gaussian2D(1.5, -2.25, 1.0, 1.0))));
            assertArrayEquals(new double[]{1.5, -2.25}, v, 1e-6);
        }

        @Test
        void histogramMeanUsesCellCentres() {
            // Uniform over [0,3] in three cells: centres 0.5, 1.5, 2.5, each weight 1/3.
            assertArrayEquals(new double[]{1.5},
                asVector(mean.exec(nv(uniformHistogram(0.0, 3.0, 3)))), 1e-6);

            // Weighted: 0.25*0.5 + 0.75*1.5 = 1.25
            HistogramValue skewed = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            assertArrayEquals(new double[]{1.25}, asVector(mean.exec(nv(skewed))), 1e-6);
        }

        @Test
        void dirichletMeanIsAlphaOverAlphaSum() {
            // E[X_i] = alpha_i / sum(alpha); for (2,3) that is (0.4, 0.6).
            assertArrayEquals(new double[]{0.4, 0.6},
                asVector(mean.exec(nv(dirichlet(2.0, 3.0)))), 1e-6);
        }

        @Test
        void dirichletMeanSumsToOne() {
            double[] v = asVector(mean.exec(nv(dirichlet(1.0, 2.0, 3.0, 4.0))));
            double sum = 0.0;
            for (double x : v) sum += x;
            assertEquals(1.0, sum, 1e-6);
        }
    }

    @Nested
    @DisplayName("prob:std")
    class StdFn {
        private final Std std = new Std();

        @Test
        void singleGaussianReturnsSqrtVariance() {
            assertArrayEquals(new double[]{3.0}, asVector(std.exec(nv(gaussian(0.0, 9.0)))), 1e-6);
        }

        @Test
        void mixtureUsesTheLawOfTotalVariance() {
            // Var = sum w_k (var_k + mu_k^2) - mu^2.
            // For 0.5*N(0,1) + 0.5*N(4,1): mu = 2, Var = 0.5*(1+0) + 0.5*(1+16) - 4 = 5.
            GMMValue g = mixture(0.5, 0.0, 1.0, 4.0, 1.0);
            assertArrayEquals(new double[]{Math.sqrt(5.0)}, asVector(std.exec(nv(g))), 1e-6);
        }

        @Test
        void separatedComponentsHaveLargerSpreadThanEitherAlone() {
            double single = asVector(std.exec(nv(gaussian(0.0, 1.0))))[0];
            double split = asVector(std.exec(nv(mixture(0.5, -10.0, 1.0, 10.0, 1.0))))[0];
            assertTrue(split > single * 5, "a widely separated mixture must be far more spread out");
        }

        @Test
        void histogramStdAccountsForWithinCellSpread() {
            // A single cell [0,1] with all the mass is uniform on that cell, whose
            // variance is width^2/12.
            HistogramValue oneCell = histogram(new double[]{0.0, 1.0}, new double[]{1.0});
            assertArrayEquals(new double[]{Math.sqrt(1.0 / 12.0)},
                asVector(std.exec(nv(oneCell))), 1e-6);
        }

        @Test
        void dirichletStdMatchesClosedForm() {
            // Var[X_i] = a_i (a0 - a_i) / (a0^2 (a0 + 1)); for (2,3): a0 = 5,
            // Var[X_0] = 2*3 / (25*6) = 0.04, sd = 0.2.
            assertArrayEquals(new double[]{0.2, 0.2},
                asVector(std.exec(nv(dirichlet(2.0, 3.0)))), 1e-6);
        }
    }

    @Nested
    @DisplayName("prob:map")
    class MapFn {
        private final Map map = new Map();

        @Test
        void returnsTheMeanOfTheHeaviestComponent() {
            GMMValue g = mixture(0.2, 1.0, 1.0, 7.0, 1.0);   // second component has 0.8
            assertArrayEquals(new double[]{7.0}, asVector(map.exec(nv(g))), 1e-6);
        }

        @Test
        void singleComponentReturnsItsMean() {
            assertArrayEquals(new double[]{-4.0}, asVector(map.exec(nv(gaussian(-4.0, 2.0)))), 1e-6);
        }

        @Test
        void histogramMapIsTheCentreOfTheHeaviestCell() {
            // Heaviest cell is [2,3), whose centre is 2.5.
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.2, 0.3, 0.5});
            assertArrayEquals(new double[]{2.5}, asVector(map.exec(nv(h))), 1e-6);
        }

        @Test
        void dirichletModeMatchesClosedFormWhenAllAlphasExceedOne() {
            // mode_i = (a_i - 1) / (a0 - k); for (3,2): a0 = 5, k = 2 -> (2/3, 1/3).
            assertArrayEquals(new double[]{2.0 / 3.0, 1.0 / 3.0},
                asVector(map.exec(nv(dirichlet(3.0, 2.0)))), 1e-6);
        }

        @Test
        void dirichletFallsBackToTheMeanWhenTheModeIsUndefined() {
            // With alpha_i <= 1 the density has no interior mode, so the mean is used.
            DirichletValue d = dirichlet(0.5, 0.5);
            assertArrayEquals(asVector(new Mean().exec(nv(d))), asVector(map.exec(nv(d))), 1e-6);
        }
    }

    @Nested
    @DisplayName("prob:modeCount")
    class ModeCountFn {
        private final ModeCount modeCount = new ModeCount();

        @Test
        void countsMixtureComponents() {
            assertEquals(1, modeCount.exec(nv(gaussian(0.0, 1.0))).getInteger().intValue());
            assertEquals(2, modeCount.exec(nv(mixture(0.5, 0.0, 1.0, 5.0, 1.0)))
                .getInteger().intValue());
        }

        @Test
        void countsComponentsOfAThreeComponentMixture() {
            GMMValue g = new GMMValue(3, 1, "full", new double[]{0.2, 0.3, 0.5},
                new double[][]{{0.0}, {1.0}, {2.0}},
                new double[][][]{{{1.0}}, {{1.0}}, {{1.0}}});
            assertEquals(3, modeCount.exec(nv(g)).getInteger().intValue());
        }

        @Test
        void rejectsNonGMMArguments() {
            assertThrows(RuntimeException.class,
                () -> modeCount.exec(nv(uniformHistogram(0.0, 1.0, 2))));
        }
    }

    @Nested
    @DisplayName("prob:mix")
    class MixFn {
        private final Mix mix = new Mix();

        @Test
        void mixtureMeanIsTheConvexCombinationOfOperandMeans() {
            // 0.25*N(0,1) + 0.75*N(8,1) has mean 6.
            GMMValue out = asGMM(mix.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(8.0, 1.0)), num(0.25)));
            assertArrayEquals(new double[]{6.0}, asVector(new Mean().exec(nv(out))), 1e-6);
        }

        @Test
        void componentCountIsTheSumOfOperandComponents() {
            GMMValue out = asGMM(mix.exec(nv(mixture(0.5, 0.0, 1.0, 1.0, 1.0)),
                nv(gaussian(5.0, 1.0)), num(0.5)));
            assertEquals(3, out.getNComponents());
        }

        @Test
        void weightsAreScaledByAlphaAndStillSumToOne() {
            GMMValue out = asGMM(mix.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(1.0, 1.0)), num(0.3)));
            double sum = 0.0;
            for (double w : out.getWeights()) sum += w;
            assertEquals(1.0, sum, 1e-9);
            double[] weights = out.getWeights().clone();
            java.util.Arrays.sort(weights);
            assertArrayEquals(new double[]{0.3, 0.7}, weights, 1e-9);
        }

        @Test
        void alphaOfOneReturnsTheFirstOperandsDistribution() {
            GMMValue out = asGMM(mix.exec(nv(gaussian(2.0, 1.0)), nv(gaussian(90.0, 1.0)), num(1.0)));
            assertArrayEquals(new double[]{2.0}, asVector(new Mean().exec(nv(out))), 1e-6);
        }

        @Test
        void histogramMixIsTheCellwiseConvexCombination() {
            HistogramValue a = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{1.0, 0.0});
            HistogramValue b = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.0, 1.0});
            HistogramValue out = asHistogram(mix.exec(nv(a), nv(b), num(0.25)));
            assertArrayEquals(new double[]{0.25, 0.75}, out.getWeights(), 1e-12);
        }

        @Test
        void rejectsAlphaOutsideTheUnitInterval() {
            assertThrows(RuntimeException.class,
                () -> mix.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(1.0, 1.0)), num(1.5)));
            assertThrows(RuntimeException.class,
                () -> mix.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(1.0, 1.0)), num(-0.1)));
        }
    }

    @Nested
    @DisplayName("prob:fuse")
    class FuseFn {
        private final Fuse fuse = new Fuse();

        @Test
        void productOfTwoGaussiansHasPrecisionWeightedMean() {
            // N(0,1) x N(2,1) is proportional to N(1, 1/2): precisions add, and the
            // mean is the precision-weighted average of the operand means.
            GMMValue out = asGMM(fuse.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(2.0, 1.0))));
            assertArrayEquals(new double[]{1.0}, asVector(new Mean().exec(nv(out))), 1e-6);
            assertArrayEquals(new double[]{Math.sqrt(0.5)},
                asVector(new Std().exec(nv(out))), 1e-6);
        }

        @Test
        void theConfidentOperandDominatesTheFusedEstimate() {
            // A prior with large variance should barely move a sharp likelihood.
            GMMValue out = asGMM(fuse.exec(nv(gaussian(0.0, 100.0)), nv(gaussian(10.0, 0.01))));
            double fusedMean = asVector(new Mean().exec(nv(out)))[0];
            assertTrue(Math.abs(fusedMean - 10.0) < 0.01,
                "fused mean should sit next to the low-variance operand, got " + fusedMean);
        }

        @Test
        void fusionNeverIncreasesUncertainty() {
            double before = asVector(new Std().exec(nv(gaussian(0.0, 4.0))))[0];
            GMMValue out = asGMM(fuse.exec(nv(gaussian(0.0, 4.0)), nv(gaussian(1.0, 4.0))));
            double after = asVector(new Std().exec(nv(out)))[0];
            assertTrue(after < before, "combining two observations must sharpen the estimate");
        }

        @Test
        void isSymmetricInItsOperands() {
            double forward = asVector(new Mean().exec(
                nv(asGMM(fuse.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(3.0, 2.0)))))))[0];
            double reverse = asVector(new Mean().exec(
                nv(asGMM(fuse.exec(nv(gaussian(3.0, 2.0)), nv(gaussian(0.0, 1.0)))))))[0];
            assertEquals(forward, reverse, 1e-6);
        }

        @Test
        void rejectsMismatchedDimensionality() {
            assertThrows(RuntimeException.class,
                () -> fuse.exec(nv(gaussian(0.0, 1.0)), nv(gaussian2D(0.0, 0.0, 1.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:quantile")
    class QuantileFn {
        private final Quantile quantile = new Quantile();

        @Test
        void standardNormalQuantilesMatchKnownValues() {
            GMMValue g = gaussian(0.0, 1.0);
            assertEquals(0.0, quantile.exec(nv(g), num(0.5)).getDouble(), 1e-4);
            // The 97.5th percentile of the standard normal.
            assertEquals(1.959964, quantile.exec(nv(g), num(0.975)).getDouble(), 1e-3);
            assertEquals(-1.281552, quantile.exec(nv(g), num(0.10)).getDouble(), 1e-3);
        }

        @Test
        void shiftedAndScaledGaussianShiftsAndScalesItsQuantiles() {
            // For N(mu, sigma^2) the p-quantile is mu + sigma * z_p.
            GMMValue g = gaussian(10.0, 4.0);
            assertEquals(10.0 + 2.0 * 1.959964, quantile.exec(nv(g), num(0.975)).getDouble(), 1e-2);
        }

        @Test
        void isMonotoneInTheProbability() {
            GMMValue g = mixture(0.4, -2.0, 1.0, 3.0, 2.0);
            double previous = Double.NEGATIVE_INFINITY;
            for (double p = 0.05; p <= 0.95; p += 0.05) {
                double q = quantile.exec(nv(g), num(p)).getDouble();
                assertTrue(q >= previous, "quantile must be non-decreasing at p=" + p);
                previous = q;
            }
        }

        @Test
        void histogramQuantileInvertsThePiecewiseUniformCdf() {
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(0.0, quantile.exec(nv(h), num(0.0)).getDouble(), EXACT);
            assertEquals(1.5, quantile.exec(nv(h), num(0.5)).getDouble(), EXACT);
            assertEquals(3.0, quantile.exec(nv(h), num(1.0)).getDouble(), EXACT);
            // One sixth of the mass sits below 0.5.
            assertEquals(0.5, quantile.exec(nv(h), num(1.0 / 6.0)).getDouble(), 1e-9);
        }

        @Test
        void histogramQuantileInvertsTheCdfOnAnUnevenGrid() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.2, 0.5, 0.3});
            // 0.45 of the mass lies below 1.5 (0.2 plus half of 0.5).
            assertEquals(1.5, quantile.exec(nv(h), num(0.45)).getDouble(), 1e-9);
            assertEquals(1.0, quantile.exec(nv(h), num(0.2)).getDouble(), 1e-9);
        }

        @Test
        void dirichletQuantileUsesTheBetaMarginalOfDimensionZero() {
            // X0 of Dir(2,2) is Beta(2,2), whose median is 1/2 by symmetry.
            assertEquals(0.5, quantile.exec(nv(dirichlet(2.0, 2.0)), num(0.5)).getDouble(), 1e-4);
            // Beta(1,1) is uniform, so its p-quantile is p.
            assertEquals(0.3, quantile.exec(nv(dirichlet(1.0, 1.0)), num(0.3)).getDouble(), 1e-4);
        }

        @Test
        void rejectsProbabilitiesOutsideTheUnitInterval() {
            GMMValue g = gaussian(0.0, 1.0);
            assertThrows(RuntimeException.class, () -> quantile.exec(nv(g), num(-0.1)));
            assertThrows(RuntimeException.class, () -> quantile.exec(nv(g), num(1.1)));
        }

        @Test
        void rejectsMultidimensionalGMMs() {
            // A multivariate quantile is not uniquely defined.
            assertThrows(RuntimeException.class,
                () -> quantile.exec(nv(gaussian2D(0.0, 0.0, 1.0, 1.0)), num(0.5)));
        }
    }

    @Nested
    @DisplayName("prob:histmean")
    class HistMeanFn {
        private final HistogramMean histMean = new HistogramMean();

        @Test
        void weightsCellCentresByCellMass() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            assertArrayEquals(new double[]{1.25}, asVector(histMean.exec(nv(h))), 1e-6);
        }

        @Test
        void returnsOneEntryPerAxis() {
            HistogramValue h = new HistogramValue(2,
                new double[][]{{0.0, 1.0, 2.0}, {0.0, 2.0, 4.0}},
                new double[]{0.25, 0.25, 0.25, 0.25});
            // Both marginals are uniform, so the centres are 1.0 and 2.0.
            assertArrayEquals(new double[]{1.0, 2.0}, asVector(histMean.exec(nv(h))), 1e-6);
        }

        @Test
        void agreesWithThePolymorphicMean() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.2, 0.5, 0.3});
            assertArrayEquals(asVector(new Mean().exec(nv(h))),
                asVector(histMean.exec(nv(h))), 1e-9);
        }

        @Test
        void rejectsNonHistogramArguments() {
            assertThrows(RuntimeException.class, () -> histMean.exec(nv(gaussian(0.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:sample")
    class SampleFn {
        private final Sample sample = new Sample();

        @Test
        void returnsTheRequestedNumberOfRowsWithOneColumnPerDimension() {
            double[][] rows = asMatrix(sample.exec(nv(gaussian(0.0, 1.0)), intNum(50)));
            assertEquals(50, rows.length);
            assertEquals(1, rows[0].length);

            double[][] rows2d = asMatrix(sample.exec(nv(gaussian2D(0.0, 0.0, 1.0, 1.0)), intNum(20)));
            assertEquals(20, rows2d.length);
            assertEquals(2, rows2d[0].length);
        }

        @Test
        void gaussianSampleMeanApproachesTheDistributionMean() {
            double[][] rows = asMatrix(sample.exec(nv(gaussian(5.0, 1.0)), intNum(10000)));
            double sum = 0.0;
            for (double[] row : rows) sum += row[0];
            // Standard error is 1/sqrt(10000) = 0.01, so 0.1 is a ten-sigma band and
            // will not flake even though the draw is unseeded.
            assertEquals(5.0, sum / rows.length, 0.1);
        }

        @Test
        void gaussianSampleSpreadApproachesTheDistributionStandardDeviation() {
            double[][] rows = asMatrix(sample.exec(nv(gaussian(0.0, 9.0)), intNum(10000)));
            double sum = 0.0;
            for (double[] row : rows) sum += row[0];
            double mean = sum / rows.length;
            double ss = 0.0;
            for (double[] row : rows) ss += (row[0] - mean) * (row[0] - mean);
            assertEquals(3.0, Math.sqrt(ss / rows.length), 0.2);
        }

        @Test
        void enforcesTheDocumentedSampleCountCap() {
            // The cap bounds the cost of a single SPARQL binding; it is configurable
            // through -Dprobsparql.sample.max.
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> sample.exec(nv(gaussian(0.0, 1.0)), intNum(10001)));
            assertTrue(error.getMessage().contains("probsparql.sample.max"),
                "the error should name the override property, got: " + error.getMessage());
            // The boundary itself is allowed.
            assertEquals(10000, asMatrix(sample.exec(nv(gaussian(0.0, 1.0)), intNum(10000))).length);
        }

        @Test
        void histogramSamplesStayInsideTheSupport() {
            double[][] rows = asMatrix(sample.exec(nv(uniformHistogram(2.0, 5.0, 3)), intNum(500)));
            for (double[] row : rows) {
                assertTrue(row[0] >= 2.0 && row[0] <= 5.0,
                    "sample outside histogram support: " + row[0]);
            }
        }

        @Test
        void histogramSamplesRespectCellMass() {
            // All mass in the last cell [2,3): every draw must land there.
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.0, 0.0, 1.0});
            for (double[] row : asMatrix(sample.exec(nv(h), intNum(200)))) {
                assertTrue(row[0] >= 2.0 && row[0] <= 3.0, "expected the loaded cell, got " + row[0]);
            }
        }

        @Test
        void dirichletSamplesLieOnTheSimplex() {
            double[][] rows = asMatrix(sample.exec(nv(dirichlet(2.0, 3.0, 4.0)), intNum(200)));
            for (double[] row : rows) {
                assertEquals(3, row.length);
                double sum = 0.0;
                for (double x : row) {
                    assertTrue(x >= 0.0 && x <= 1.0, "component outside [0,1]: " + x);
                    sum += x;
                }
                assertEquals(1.0, sum, 1e-9, "Dirichlet draw must lie on the simplex");
            }
        }

        @Test
        void rejectsNonPositiveCounts() {
            assertThrows(RuntimeException.class,
                () -> sample.exec(nv(gaussian(0.0, 1.0)), intNum(0)));
            assertThrows(RuntimeException.class,
                () -> sample.exec(nv(gaussian(0.0, 1.0)), intNum(-5)));
        }
    }
}
