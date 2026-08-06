package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.functions.thresholding.CDF;
import org.apache.jena.probsparql.functions.thresholding.HistogramCDF;
import org.apache.jena.probsparql.functions.thresholding.LogCDF;
import org.apache.jena.probsparql.functions.thresholding.LogPDF;
import org.apache.jena.probsparql.functions.thresholding.PDF;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.apache.jena.probsparql.functions.FunctionTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness of the five thresholding functions, checked against closed-form
 * densities and exact discrete sums.
 */
class ThresholdingCorrectnessTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
    }

    @Nested
    @DisplayName("prob:pdf")
    class Pdf {
        private final PDF pdf = new PDF();

        @Test
        void standardNormalMatchesClosedForm() {
            GMMValue g = gaussian(0.0, 1.0);
            assertEquals(STD_NORMAL_PEAK, pdf.exec(nv(g), num(0.0)).getDouble(), EXACT);
            assertEquals(normalPdf(1.0, 0.0, 1.0), pdf.exec(nv(g), num(1.0)).getDouble(), EXACT);
            assertEquals(normalPdf(-2.5, 0.0, 1.0), pdf.exec(nv(g), num(-2.5)).getDouble(), EXACT);
        }

        @Test
        void nonUnitVarianceMatchesClosedForm() {
            GMMValue g = gaussian(3.0, 4.0);
            assertEquals(normalPdf(3.0, 3.0, 4.0), pdf.exec(nv(g), num(3.0)).getDouble(), EXACT);
            assertEquals(normalPdf(5.0, 3.0, 4.0), pdf.exec(nv(g), num(5.0)).getDouble(), EXACT);
        }

        @Test
        void mixtureIsTheWeightedSumOfComponents() {
            GMMValue g = mixture(0.3, -2.0, 1.0, 2.0, 4.0);
            double expected = 0.3 * normalPdf(0.5, -2.0, 1.0) + 0.7 * normalPdf(0.5, 2.0, 4.0);
            assertEquals(expected, pdf.exec(nv(g), num(0.5)).getDouble(), EXACT);
        }

        @Test
        void twoDimensionalDensityFactorisesOverIndependentAxes() {
            GMMValue g = gaussian2D(0.0, 0.0, 1.0, 1.0);
            // Standard bivariate normal at the origin is 1/(2*pi).
            assertEquals(1.0 / (2.0 * Math.PI), pdf.exec(nv(g), str("[0.0,0.0]")).getDouble(), EXACT);

            GMMValue h = gaussian2D(1.0, -1.0, 2.0, 0.5);
            double expected = normalPdf(0.0, 1.0, 2.0) * normalPdf(0.0, -1.0, 0.5);
            assertEquals(expected, pdf.exec(nv(h), str("[0.0,0.0]")).getDouble(), EXACT);
        }

        @Test
        void diagAndFullCovarianceLayoutsAgree() {
            double full = pdf.exec(nv(gaussian2D(1.0, 2.0, 3.0, 4.0)), str("[0.5,1.5]")).getDouble();
            double diag = pdf.exec(nv(gaussian2DDiag(1.0, 2.0, 3.0, 4.0)), str("[0.5,1.5]")).getDouble();
            assertEquals(full, diag, EXACT);
        }

        @Test
        void histogramIsPiecewiseConstantMassOverWidth() {
            // Three equal cells over [0,3]: each holds mass 1/3 across width 1.
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(1.0 / 3.0, pdf.exec(nv(h), num(0.5)).getDouble(), EXACT);
            assertEquals(1.0 / 3.0, pdf.exec(nv(h), num(2.5)).getDouble(), EXACT);
            // Outside the support the density is zero.
            assertEquals(0.0, pdf.exec(nv(h), num(-1.0)).getDouble(), EXACT);
            assertEquals(0.0, pdf.exec(nv(h), num(4.0)).getDouble(), EXACT);
        }

        @Test
        void histogramDensityAccountsForUnequalCellWidths() {
            // Cell [0,1) holds 0.25 -> density 0.25; cell [1,5) holds 0.75 over width 4.
            HistogramValue h = histogram(new double[]{0.0, 1.0, 5.0}, new double[]{0.25, 0.75});
            assertEquals(0.25, pdf.exec(nv(h), num(0.5)).getDouble(), EXACT);
            assertEquals(0.75 / 4.0, pdf.exec(nv(h), num(3.0)).getDouble(), EXACT);
        }

        @Test
        void uniformDirichletHasUnitDensityOnTheSimplex() {
            // Dir(1,1) is uniform on the 1-simplex, so B(1,1) = 1 and the density is 1.
            DirichletValue d = dirichlet(1.0, 1.0);
            assertEquals(1.0, pdf.exec(nv(d), str("[0.5,0.5]")).getDouble(), 1e-6);
            assertEquals(1.0, pdf.exec(nv(d), str("[0.2,0.8]")).getDouble(), 1e-6);
        }

        @Test
        void rejectsNonDistributionArgument() {
            assertThrows(RuntimeException.class, () -> pdf.exec(num(1.0), num(0.0)));
        }
    }

    @Nested
    @DisplayName("prob:cdf")
    class Cdf {
        private final CDF cdf = new CDF();

        @Test
        void standardNormalMatchesClosedForm() {
            GMMValue g = gaussian(0.0, 1.0);
            assertEquals(0.5, cdf.exec(nv(g), num(0.0)).getDouble(), 1e-6);
            assertEquals(normalCdf(1.96, 0.0, 1.0), cdf.exec(nv(g), num(1.96)).getDouble(), 1e-6);
            assertEquals(normalCdf(-1.0, 0.0, 1.0), cdf.exec(nv(g), num(-1.0)).getDouble(), 1e-6);
        }

        @Test
        void isMonotoneAndBounded() {
            GMMValue g = mixture(0.4, -3.0, 1.0, 4.0, 2.0);
            double previous = 0.0;
            for (double x = -12.0; x <= 12.0; x += 0.5) {
                double value = cdf.exec(nv(g), num(x)).getDouble();
                assertTrue(value >= previous - 1e-9, "CDF must be non-decreasing at x=" + x);
                assertTrue(value >= 0.0 && value <= 1.0, "CDF must lie in [0,1] at x=" + x);
                previous = value;
            }
            assertEquals(0.0, cdf.exec(nv(g), num(-1e6)).getDouble(), 1e-9);
            assertEquals(1.0, cdf.exec(nv(g), num(1e6)).getDouble(), 1e-9);
        }

        @Test
        void mixtureIsTheWeightedSumOfComponentCdfs() {
            GMMValue g = mixture(0.25, 0.0, 1.0, 5.0, 4.0);
            double expected = 0.25 * normalCdf(1.0, 0.0, 1.0) + 0.75 * normalCdf(1.0, 5.0, 4.0);
            assertEquals(expected, cdf.exec(nv(g), num(1.0)).getDouble(), 1e-6);
        }

        @Test
        void histogramInterpolatesLinearlyWithinACell() {
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(0.0, cdf.exec(nv(h), num(0.0)).getDouble(), EXACT);
            assertEquals(1.0 / 3.0, cdf.exec(nv(h), num(1.0)).getDouble(), EXACT);
            assertEquals(0.5, cdf.exec(nv(h), num(1.5)).getDouble(), EXACT);
            assertEquals(1.0, cdf.exec(nv(h), num(3.0)).getDouble(), EXACT);
        }

        @Test
        void dirichletUsesTheBetaMarginalOfDimensionZero() {
            // X0 of Dir(2,2) is Beta(2,2), whose CDF at 1/2 is 1/2 by symmetry.
            DirichletValue d = dirichlet(2.0, 2.0);
            assertEquals(0.5, cdf.exec(nv(d), num(0.5)).getDouble(), 1e-6);
            // Beta(1,1) is uniform on [0,1].
            assertEquals(0.25, cdf.exec(nv(dirichlet(1.0, 1.0)), num(0.25)).getDouble(), 1e-6);
        }
    }

    @Nested
    @DisplayName("prob:logpdf")
    class LogPdf {
        private final LogPDF logPdf = new LogPDF();

        @Test
        void standardNormalMatchesClosedForm() {
            GMMValue g = gaussian(0.0, 1.0);
            assertEquals(STD_NORMAL_PEAK_LOG, logPdf.exec(nv(g), num(0.0)).getDouble(), EXACT);
            assertEquals(Math.log(normalPdf(2.0, 0.0, 1.0)),
                logPdf.exec(nv(g), num(2.0)).getDouble(), EXACT);
        }

        @Test
        void agreesWithTheLogarithmOfPdfAcrossTheSupport() {
            GMMValue g = mixture(0.6, -1.0, 0.5, 3.0, 2.0);
            PDF pdf = new PDF();
            for (double x = -5.0; x <= 8.0; x += 0.25) {
                double direct = logPdf.exec(nv(g), num(x)).getDouble();
                double viaPdf = Math.log(pdf.exec(nv(g), num(x)).getDouble());
                assertEquals(viaPdf, direct, 1e-9, "mismatch at x=" + x);
            }
        }

        @Test
        void staysFiniteWhereThePdfUnderflows() {
            // At 40 sigma the density underflows to 0, but its logarithm is representable;
            // this is the whole reason the function exists separately from prob:pdf.
            GMMValue g = gaussian(0.0, 1.0);
            double value = logPdf.exec(nv(g), num(40.0)).getDouble();
            assertTrue(Double.isFinite(value), "log-density must stay finite far into the tail");
            assertEquals(-0.5 * 1600.0 - 0.5 * Math.log(2.0 * Math.PI), value, 1e-9);
            assertEquals(0.0, new PDF().exec(nv(g), num(40.0)).getDouble(), 0.0);
        }

        @Test
        void histogramOutsideSupportIsNegativeInfinity() {
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(Double.NEGATIVE_INFINITY, logPdf.exec(nv(h), num(-1.0)).getDouble(), 0.0);
            assertEquals(Math.log(1.0 / 3.0), logPdf.exec(nv(h), num(1.5)).getDouble(), EXACT);
        }
    }

    @Nested
    @DisplayName("prob:logcdf")
    class LogCdf {
        private final LogCDF logCdf = new LogCDF();

        @Test
        void isTheLogarithmOfTheCdf() {
            GMMValue g = gaussian(0.0, 1.0);
            assertEquals(Math.log(0.5), logCdf.exec(nv(g), num(0.0)).getDouble(), 1e-6);

            CDF cdf = new CDF();
            for (double x = -3.0; x <= 3.0; x += 0.5) {
                double expected = Math.log(cdf.exec(nv(g), num(x)).getDouble());
                assertEquals(expected, logCdf.exec(nv(g), num(x)).getDouble(), 1e-9, "at x=" + x);
            }
        }

        @Test
        void isNegativeInfinityWhereTheCdfIsZero() {
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(Double.NEGATIVE_INFINITY, logCdf.exec(nv(h), num(0.0)).getDouble(), 0.0);
        }

        @Test
        void isZeroAtTheTopOfTheSupport() {
            HistogramValue h = uniformHistogram(0.0, 3.0, 3);
            assertEquals(0.0, logCdf.exec(nv(h), num(3.0)).getDouble(), EXACT);
        }
    }

    @Nested
    @DisplayName("prob:histcdf")
    class HistCdf {
        private final HistogramCDF histCdf = new HistogramCDF();

        @Test
        void accumulatesCellMassExactly() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0, 3.0},
                new double[]{0.2, 0.5, 0.3});
            assertEquals(0.0, histCdf.exec(nv(h), num(0.0)).getDouble(), EXACT);
            assertEquals(0.2, histCdf.exec(nv(h), num(1.0)).getDouble(), EXACT);
            assertEquals(0.7, histCdf.exec(nv(h), num(2.0)).getDouble(), EXACT);
            assertEquals(1.0, histCdf.exec(nv(h), num(3.0)).getDouble(), EXACT);
            // Halfway through the second cell: 0.2 + 0.5*0.5.
            assertEquals(0.45, histCdf.exec(nv(h), num(1.5)).getDouble(), EXACT);
        }

        @Test
        void twoDimensionalGridGivesTheJointCdf() {
            // 2x2 grid on [0,2]^2 with masses [[0.1,0.2],[0.3,0.4]] in row-major order.
            HistogramValue h = new HistogramValue(2,
                new double[][]{{0.0, 1.0, 2.0}, {0.0, 1.0, 2.0}},
                new double[]{0.1, 0.2, 0.3, 0.4});
            // P(X<=1, Y<=1) is exactly the first cell.
            assertEquals(0.1, histCdf.exec(nv(h), str("[1.0,1.0]")).getDouble(), EXACT);
            // P(X<=2, Y<=1) covers cells (0,0) and (1,0).
            assertEquals(0.4, histCdf.exec(nv(h), str("[2.0,1.0]")).getDouble(), EXACT);
            assertEquals(1.0, histCdf.exec(nv(h), str("[2.0,2.0]")).getDouble(), EXACT);
        }

        @Test
        void agreesWithThePolymorphicCdf() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.4, 0.6});
            for (double x = 0.0; x <= 2.0; x += 0.25) {
                assertEquals(new CDF().exec(nv(h), num(x)).getDouble(),
                    histCdf.exec(nv(h), num(x)).getDouble(), EXACT, "at x=" + x);
            }
        }

        @Test
        void rejectsNonHistogramArgument() {
            assertThrows(RuntimeException.class,
                () -> histCdf.exec(nv(gaussian(0.0, 1.0)), num(0.0)));
        }
    }
}
