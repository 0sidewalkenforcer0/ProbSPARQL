package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.ProbSPARQL;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.apache.jena.probsparql.exp2.Exp2PruningHolder;
import org.apache.jena.probsparql.exp2.PruningStats;
import org.apache.jena.probsparql.functions.comparison.HistogramJSD;
import org.apache.jena.probsparql.functions.comparison.JSDMode;
import org.apache.jena.probsparql.functions.comparison.JSDivergence;
import org.apache.jena.probsparql.functions.comparison.JSDivergenceConfig;
import org.apache.jena.probsparql.functions.comparison.KLDivergence;
import org.apache.jena.probsparql.functions.comparison.LastDivJoinStats;
import org.apache.jena.probsparql.functions.comparison.PolyJSD;
import org.apache.jena.probsparql.functions.comparison.SameDistribution;
import org.apache.jena.probsparql.functions.comparison.SameTerm;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.apache.jena.probsparql.functions.FunctionTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness of the eight comparison functions.
 *
 * <p>Divergences are checked against closed forms where one exists (Gaussian KL,
 * discrete histogram sums, the {@code log 2} ceiling for disjoint support) and against
 * their defining identities otherwise (symmetry, non-negativity, zero at identity).
 * Monte Carlo paths use a tolerance sized to their sampling budget.</p>
 */
class ComparisonCorrectnessTest {

    @BeforeAll
    static void setUp() {
        ProbSPARQL.init();
        System.setProperty("probsparql.mode", JSDivergenceConfig.MODE_GT_10K);
    }

    @Nested
    @DisplayName("prob:kldivergence")
    class Kl {
        private final KLDivergence kl = new KLDivergence();

        private double kl(GMMValue p, GMMValue q) {
            return kl.exec(nv(p), nv(q)).getDouble();
        }

        @Test
        void gaussianPairMatchesTheClosedForm() {
            // KL(N(0,1) || N(1,1)) = (v1 + (m1-m2)^2)/(2*v2) - 1/2 = 1/2.
            assertEquals(gaussianKL(0.0, 1.0, 1.0, 1.0),
                kl(gaussian(0.0, 1.0), gaussian(1.0, 1.0)), MONTE_CARLO);
            assertEquals(0.5, kl(gaussian(0.0, 1.0), gaussian(1.0, 1.0)), MONTE_CARLO);
        }

        @Test
        void differingVarianceMatchesTheClosedForm() {
            assertEquals(gaussianKL(0.0, 1.0, 0.0, 4.0),
                kl(gaussian(0.0, 1.0), gaussian(0.0, 4.0)), MONTE_CARLO);
        }

        @Test
        void identicalDistributionsGiveZero() {
            assertEquals(0.0, kl(gaussian(2.0, 3.0), gaussian(2.0, 3.0)), 1e-9);
        }

        @Test
        void isAsymmetric() {
            double pq = kl(gaussian(0.0, 1.0), gaussian(0.0, 4.0));
            double qp = kl(gaussian(0.0, 4.0), gaussian(0.0, 1.0));
            assertTrue(Math.abs(pq - qp) > 0.1,
                "KL must differ by direction, got " + pq + " and " + qp);
            // Both directions still match their own closed forms.
            assertEquals(gaussianKL(0.0, 4.0, 0.0, 1.0), qp, MONTE_CARLO);
        }

        @Test
        void histogramPairIsTheExactDiscreteSum() {
            // sum p_i ln(p_i/q_i) for p = (0.5,0.5), q = (0.25,0.75)
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            double expected = 0.5 * Math.log(0.5 / 0.25) + 0.5 * Math.log(0.5 / 0.75);
            assertEquals(expected, kl.exec(nv(p), nv(q)).getDouble(), EXACT);
        }

        @Test
        void histogramWithZeroDenominatorMassIsInfinite() {
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{1.0, 0.0});
            assertEquals(Double.POSITIVE_INFINITY, kl.exec(nv(p), nv(q)).getDouble(), 0.0);
        }

        @Test
        void histogramZeroNumeratorContributesNothing() {
            // The 0*log(0/q) convention means the empty cell must not poison the sum.
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{1.0, 0.0});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            assertEquals(Math.log(2.0), kl.exec(nv(p), nv(q)).getDouble(), EXACT);
        }

        @Test
        void dirichletPairUsesTheClosedForm() {
            // KL(Dir(a)||Dir(a)) = 0, and a genuine difference is strictly positive.
            assertEquals(0.0,
                kl.exec(nv(dirichlet(2.0, 3.0)), nv(dirichlet(2.0, 3.0))).getDouble(), 1e-9);
            double d = kl.exec(nv(dirichlet(2.0, 3.0)), nv(dirichlet(5.0, 1.0))).getDouble();
            assertTrue(d > 0.0 && Double.isFinite(d), "expected a positive finite value, got " + d);
        }

        @Test
        void isNonNegative() {
            for (double shift = 0.0; shift <= 3.0; shift += 0.5) {
                double value = kl(gaussian(0.0, 1.0), gaussian(shift, 1.0 + shift));
                assertTrue(value >= -1e-9, "KL must be non-negative, got " + value);
            }
        }

        @Test
        void rejectsMismatchedDimensionality() {
            assertThrows(RuntimeException.class,
                () -> kl.exec(nv(gaussian(0.0, 1.0)), nv(gaussian2D(0.0, 0.0, 1.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:jsdivergence (legacy GMM)")
    class LegacyJsd {
        private final JSDivergence jsd = new JSDivergence();

        @Test
        void identicalDistributionsGiveZero() {
            assertEquals(0.0,
                jsd.exec(nv(gaussian(1.0, 2.0)), nv(gaussian(1.0, 2.0))).getDouble(), 1e-9);
        }

        @Test
        void disjointSupportApproachesLog2() {
            double value = jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(100.0, 1.0))).getDouble();
            assertEquals(LN2, value, 1e-6);
        }

        @Test
        void staysWithinTheTheoreticalRange() {
            for (double shift = 0.0; shift <= 8.0; shift += 1.0) {
                double value = jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(shift, 1.0))).getDouble();
                assertTrue(value >= -1e-9 && value <= LN2 + 1e-6,
                    "JSD must lie in [0, ln2], got " + value + " at shift " + shift);
            }
        }

        @Test
        void growsMonotonicallyWithSeparation() {
            double near = jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(0.5, 1.0))).getDouble();
            double far = jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(4.0, 1.0))).getDouble();
            assertTrue(far > near, "separating the modes must increase divergence");
        }

        @Test
        void rejectsNonGMMArguments() {
            assertThrows(RuntimeException.class,
                () -> jsd.exec(nv(uniformHistogram(0.0, 1.0, 2)), nv(gaussian(0.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:histjsd")
    class HistJsd {
        private final HistogramJSD histJsd = new HistogramJSD();

        @Test
        void disjointSupportGivesExactlyLog2() {
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{1.0, 0.0});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.0, 1.0});
            assertEquals(LN2, histJsd.exec(nv(p), nv(q)).getDouble(), EXACT);
        }

        @Test
        void identicalHistogramsGiveZero() {
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.3, 0.7});
            assertEquals(0.0, histJsd.exec(nv(h), nv(h)).getDouble(), EXACT);
        }

        @Test
        void matchesTheHandComputedDiscreteSum() {
            // p = (0.5,0.5), q = (0.25,0.75), m = (0.375,0.625)
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
            double klPM = 0.5 * Math.log(0.5 / 0.375) + 0.5 * Math.log(0.5 / 0.625);
            double klQM = 0.25 * Math.log(0.25 / 0.375) + 0.75 * Math.log(0.75 / 0.625);
            assertEquals(0.5 * klPM + 0.5 * klQM, histJsd.exec(nv(p), nv(q)).getDouble(), EXACT);
        }

        @Test
        void isSymmetric() {
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.2, 0.8});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.9, 0.1});
            assertEquals(histJsd.exec(nv(p), nv(q)).getDouble(),
                histJsd.exec(nv(q), nv(p)).getDouble(), EXACT);
        }

        @Test
        void rejectsHistogramsOnDifferentGrids() {
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.5, 0.5});
            HistogramValue q = histogram(new double[]{0.0, 5.0, 10.0}, new double[]{0.5, 0.5});
            assertThrows(RuntimeException.class, () -> histJsd.exec(nv(p), nv(q)));
        }
    }

    @Nested
    @DisplayName("prob:jsd (polymorphic)")
    class PolyJsd {
        private final PolyJSD jsd = new PolyJSD();

        @Test
        void histogramPathAgreesExactlyWithHistjsd() {
            HistogramValue p = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.2, 0.8});
            HistogramValue q = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.6, 0.4});
            assertEquals(new HistogramJSD().exec(nv(p), nv(q)).getDouble(),
                jsd.exec(nv(p), nv(q)).getDouble(), EXACT);
        }

        @Test
        void identicalOperandsGiveZeroForEveryDatatype() {
            assertEquals(0.0, jsd.exec(nv(gaussian(1.0, 2.0)), nv(gaussian(1.0, 2.0))).getDouble(), 1e-9);
            HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.3, 0.7});
            assertEquals(0.0, jsd.exec(nv(h), nv(h)).getDouble(), EXACT);
            assertEquals(0.0, jsd.exec(nv(dirichlet(2.0, 3.0)), nv(dirichlet(2.0, 3.0))).getDouble(), 1e-9);
        }

        @Test
        void disjointGaussiansApproachLog2() {
            assertEquals(LN2,
                jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(200.0, 1.0))).getDouble(), 1e-6);
        }

        @Test
        void staysWithinTheTheoreticalRangeForEveryDatatype() {
            double gmm = jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(2.0, 3.0))).getDouble();
            double hist = jsd.exec(nv(histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.1, 0.9})),
                nv(histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.9, 0.1}))).getDouble();
            double dir = jsd.exec(nv(dirichlet(1.0, 5.0)), nv(dirichlet(5.0, 1.0))).getDouble();
            for (double value : new double[]{gmm, hist, dir}) {
                assertTrue(value >= -1e-9 && value <= LN2 + 1e-6,
                    "JSD must lie in [0, ln2], got " + value);
            }
        }

        @Test
        void isSymmetricAcrossDatatypes() {
            assertEquals(jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(2.0, 4.0))).getDouble(),
                jsd.exec(nv(gaussian(2.0, 4.0)), nv(gaussian(0.0, 1.0))).getDouble(), 0.0);
            assertEquals(jsd.exec(nv(dirichlet(2.0, 5.0)), nv(dirichlet(4.0, 1.0))).getDouble(),
                jsd.exec(nv(dirichlet(4.0, 1.0)), nv(dirichlet(2.0, 5.0))).getDouble(), 0.0);
        }

        @Test
        void crossTypeComparisonReturnsAFiniteValueInRange() {
            double value = jsd.exec(nv(uniformHistogram(0.0, 1.0, 4)), nv(dirichlet(2.0, 2.0))).getDouble();
            assertTrue(Double.isFinite(value) && value >= 0.0 && value <= LN2 + 1e-6,
                "cross-type JSD must be finite and in range, got " + value);
        }

        @Test
        void rejectsMismatchedDimensionality() {
            assertThrows(RuntimeException.class,
                () -> jsd.exec(nv(gaussian(0.0, 1.0)), nv(gaussian2D(0.0, 0.0, 1.0, 1.0))));
        }
    }

    @Nested
    @DisplayName("prob:jsdMode")
    class JsdModeFn {
        private final JSDMode jsdMode = new JSDMode();

        private double at(String mode, GMMValue p, GMMValue q) {
            return jsdMode.exec(nv(p), nv(q), str(mode)).getDouble();
        }

        @Test
        void everyModeReturnsZeroForIdenticalOperands() {
            for (String mode : new String[]{
                    JSDivergenceConfig.MODE_V1_MC, JSDivergenceConfig.MODE_V2_STRATIFIED,
                    JSDivergenceConfig.MODE_V3_SPRT, JSDivergenceConfig.MODE_V4_BOUNDS,
                    JSDivergenceConfig.MODE_V5_ADAPTIVE, JSDivergenceConfig.MODE_GT_10K}) {
                assertEquals(0.0, at(mode, gaussian(1.0, 2.0), gaussian(1.0, 2.0)), 1e-6,
                    "mode " + mode);
            }
        }

        @Test
        void everyModeStaysWithinTheTheoreticalRange() {
            for (String mode : new String[]{
                    JSDivergenceConfig.MODE_V1_MC, JSDivergenceConfig.MODE_V2_STRATIFIED,
                    JSDivergenceConfig.MODE_V3_SPRT, JSDivergenceConfig.MODE_V4_BOUNDS,
                    JSDivergenceConfig.MODE_V5_ADAPTIVE, JSDivergenceConfig.MODE_GT_100,
                    JSDivergenceConfig.MODE_GT_1K, JSDivergenceConfig.MODE_GT_5K,
                    JSDivergenceConfig.MODE_GT_10K}) {
                double value = at(mode, gaussian(0.0, 1.0), gaussian(1.5, 2.0));
                assertTrue(value >= -1e-9 && value <= LN2 + 1e-6,
                    "mode " + mode + " produced " + value);
            }
        }

        @Test
        void groundTruthModesAgreeWithTheLegacyEstimatorOnAClearPair() {
            // Well-separated operands leave no room for estimator disagreement.
            double gt = at(JSDivergenceConfig.MODE_GT_10K, gaussian(0.0, 1.0), gaussian(50.0, 1.0));
            assertEquals(LN2, gt, 1e-6);
        }

        @Test
        void boundsModeReportsALowerBoundOnTheGroundTruth() {
            // V4 is a scoring path: it returns the analytic bound, which by construction
            // never exceeds the true divergence.
            GMMValue p = gaussian(0.0, 1.0);
            GMMValue q = gaussian(1.5, 1.0);
            double bound = at(JSDivergenceConfig.MODE_V4_BOUNDS, p, q);
            double truth = at(JSDivergenceConfig.MODE_GT_10K, p, q);
            assertTrue(bound <= truth + 0.01,
                "V4 must not exceed the ground truth: bound=" + bound + " truth=" + truth);
        }

        @Test
        void rejectsAnUnknownMode() {
            assertThrows(RuntimeException.class,
                () -> jsdMode.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(1.0, 1.0)), str("V9_NOPE")));
        }

        @Test
        void rejectsANonStringModeArgument() {
            assertThrows(RuntimeException.class,
                () -> jsdMode.exec(nv(gaussian(0.0, 1.0)), nv(gaussian(1.0, 1.0)), num(3.0)));
        }

        @Test
        void rejectsNonGMMOperands() {
            assertThrows(RuntimeException.class,
                () -> jsdMode.exec(nv(uniformHistogram(0.0, 1.0, 2)), nv(gaussian(0.0, 1.0)),
                    str(JSDivergenceConfig.MODE_V1_MC)));
        }
    }

    @Nested
    @DisplayName("prob:sameTerm")
    class SameTermFn {
        private final SameTerm sameTerm = new SameTerm();

        @Test
        void identicalLexicalFormsAreTheSameTerm() {
            GMMValue g = gaussian(1.0, 2.0);
            assertTrue(sameTerm.exec(nv(g), nv(g)).getBoolean());
        }

        @Test
        void differentDistributionsAreNotTheSameTerm() {
            assertFalse(sameTerm.exec(nv(gaussian(1.0, 2.0)), nv(gaussian(3.0, 2.0))).getBoolean());
        }

        @Test
        void comparesLexicalFormRatherThanValue() {
            // Component order is canonicalised on construction, so two mixtures written
            // in different orders serialise identically and are the same term.
            GMMValue ab = mixture(0.4, 1.0, 1.0, 5.0, 2.0);
            GMMValue ba = new GMMValue(2, 1, "full", new double[]{0.6, 0.4},
                new double[][]{{5.0}, {1.0}}, new double[][][]{{{2.0}}, {{1.0}}});
            assertEquals(ab.toJSON(), ba.toJSON(), "component ordering should be canonical");
            assertTrue(sameTerm.exec(nv(ab), nv(ba)).getBoolean());
        }

        @Test
        void distributionsOfDifferentDatatypesAreNotTheSameTerm() {
            assertFalse(sameTerm.exec(nv(gaussian(1.0, 2.0)),
                nv(uniformHistogram(0.0, 1.0, 2))).getBoolean());
        }
    }

    @Nested
    @DisplayName("prob:sameDistribution")
    class SameDistributionFn {
        private final SameDistribution sameDistribution = new SameDistribution();

        @Test
        void equalDistributionsCompareEqual() {
            assertTrue(sameDistribution.exec(nv(gaussian(1.0, 2.0)),
                nv(gaussian(1.0, 2.0))).getBoolean());
        }

        @Test
        void differentParametersCompareUnequal() {
            assertFalse(sameDistribution.exec(nv(gaussian(1.0, 2.0)),
                nv(gaussian(1.0, 2.5))).getBoolean());
        }

        @Test
        void permutedMixtureComponentsDenoteTheSameDistribution() {
            GMMValue ab = mixture(0.4, 1.0, 1.0, 5.0, 2.0);
            GMMValue ba = new GMMValue(2, 1, "full", new double[]{0.6, 0.4},
                new double[][]{{5.0}, {1.0}}, new double[][][]{{{2.0}}, {{1.0}}});
            assertTrue(sameDistribution.exec(nv(ab), nv(ba)).getBoolean());
        }

        @Test
        void histogramsAndDirichletsCompareByValue() {
            assertTrue(sameDistribution.exec(nv(uniformHistogram(0.0, 3.0, 3)),
                nv(uniformHistogram(0.0, 3.0, 3))).getBoolean());
            assertTrue(sameDistribution.exec(nv(dirichlet(2.0, 3.0)),
                nv(dirichlet(2.0, 3.0))).getBoolean());
            assertFalse(sameDistribution.exec(nv(dirichlet(2.0, 3.0)),
                nv(dirichlet(3.0, 2.0))).getBoolean());
        }
    }

    @Nested
    @DisplayName("prob:lastDivJoinStats")
    class LastDivJoinStatsFn {
        private final LastDivJoinStats stats = new LastDivJoinStats();

        @AfterEach
        void clearPublishedStats() {
            Exp2PruningHolder.clearLast();
            Exp2PruningHolder.clear();
        }

        private PruningStats publish() {
            PruningStats s = new PruningStats();
            s.totalPairs = 100;
            s.prunedByDim = 10;
            s.prunedByDiscretizedJSD = 65;
            s.prunedByVariance = 0;
            s.prunedByBounds = 0;
            s.computedFullJSD = 25;
            s.resultCount = 7;
            s.evaluationFailures = 2;
            Exp2PruningHolder.set(s);
            return s;
        }

        @Test
        void exposesEveryPublishedCounter() {
            publish();
            assertEquals(100, stats.exec(str("totalPairs")).getInteger().intValue());
            assertEquals(10, stats.exec(str("prunedDim")).getInteger().intValue());
            assertEquals(65, stats.exec(str("prunedDiscJSD")).getInteger().intValue());
            assertEquals(0, stats.exec(str("prunedVar")).getInteger().intValue());
            assertEquals(0, stats.exec(str("prunedBounds")).getInteger().intValue());
            assertEquals(25, stats.exec(str("fullJSD")).getInteger().intValue());
            assertEquals(7, stats.exec(str("resultCount")).getInteger().intValue());
            assertEquals(2, stats.exec(str("failures")).getInteger().intValue());
        }

        @Test
        void keepsTheHistoricalPrunedMeanAlias() {
            // Existing benchmark queries use the old key; it must resolve to the same
            // counter as the accurate name.
            publish();
            assertEquals(stats.exec(str("prunedDiscJSD")).getInteger(),
                stats.exec(str("prunedMean")).getInteger());
        }

        @Test
        void reportsThePruningRateDerivedFromTheCounters() {
            publish();
            // (100 - 25) / 100
            assertEquals(0.75, stats.exec(str("pruningRate")).getDouble(), 1e-12);
        }

        @Test
        void failsWhenNoStatsHaveBeenPublished() {
            Exp2PruningHolder.clearLast();
            assertThrows(IllegalStateException.class, () -> stats.exec(str("totalPairs")));
        }

        @Test
        void rejectsAnUnknownFieldName() {
            publish();
            assertThrows(RuntimeException.class, () -> stats.exec(str("noSuchField")));
        }

        @Test
        void rejectsANonStringFieldName() {
            publish();
            assertThrows(RuntimeException.class, () -> stats.exec(num(1.0)));
        }
    }
}
