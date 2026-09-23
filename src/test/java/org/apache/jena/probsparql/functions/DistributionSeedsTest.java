package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMValue;
import org.apache.jena.probsparql.datatypes.HistogramValue;
import org.junit.jupiter.api.Test;

import static org.apache.jena.probsparql.functions.FunctionTestSupport.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Properties the seeding and ordering helpers must have for symmetric estimators to be
 * reproducible.
 *
 * <p>These are the foundations the determinism guarantees rest on, so they are pinned
 * directly rather than only through the estimators that use them.</p>
 */
class DistributionSeedsTest {

    // ------------------------------------------------------------------- seeding

    @Test
    void pairSeedIsIndependentOfArgumentOrder() {
        GMMValue a = gaussian(0.0, 1.0);
        GMMValue b = gaussian(3.0, 2.0);
        assertEquals(DistributionSeeds.forPair(a, b), DistributionSeeds.forPair(b, a));
    }

    @Test
    void orderedPairSeedDependsOnArgumentOrder() {
        // KL divergence is asymmetric, so its two directions must not share a stream.
        GMMValue a = gaussian(0.0, 1.0);
        GMMValue b = gaussian(3.0, 2.0);
        assertNotEquals(DistributionSeeds.forOrderedPair(a, b),
            DistributionSeeds.forOrderedPair(b, a));
    }

    @Test
    void differentPairsGetDifferentSeeds() {
        // A single fixed seed would correlate the estimates of unrelated pairs.
        GMMValue a = gaussian(0.0, 1.0);
        assertNotEquals(DistributionSeeds.forPair(a, gaussian(1.0, 1.0)),
            DistributionSeeds.forPair(a, gaussian(2.0, 1.0)));
    }

    @Test
    void equalOperandsProduceEqualSeeds() {
        // Value equality, not identity: two literals parsed separately must agree.
        assertEquals(DistributionSeeds.forPair(gaussian(1.0, 2.0), gaussian(3.0, 4.0)),
            DistributionSeeds.forPair(gaussian(1.0, 2.0), gaussian(3.0, 4.0)));
    }

    // ------------------------------------------------------------------ ordering

    /**
     * The property the whole mechanism depends on. If both directions answered true,
     * neither call would swap and the operand order would follow the argument order
     * again, silently undoing the symmetry guarantee.
     */
    private static void assertAntisymmetric(Object a, Object b) {
        boolean ab = DistributionSeeds.inCanonicalOrder(a, b);
        boolean ba = DistributionSeeds.inCanonicalOrder(b, a);
        assertTrue(ab ^ ba,
            "exactly one direction must be canonical for distinct operands, got "
                + ab + " and " + ba + " for " + a + " / " + b);
    }

    @Test
    void orderingIsAntisymmetricForDistinctGMMs() {
        assertAntisymmetric(gaussian(0.0, 1.0), gaussian(5.0, 2.0));
        assertAntisymmetric(mixture(0.3, 0.0, 1.0, 4.0, 1.0), gaussian(1.0, 1.0));
    }

    @Test
    void orderingIsAntisymmetricForGMMsSharingAnAbbreviatedToString() {
        // Regression: the tie-break used to read toString(), which for GMMValue reports
        // only the component count, dimensionality and covariance type. Every pair of
        // single-component 1-D "full" mixtures therefore tied, so a hash collision would
        // have made both directions canonical.
        GMMValue a = gaussian(0.0, 1.0);
        GMMValue b = gaussian(9.9, 2.5);
        assertEquals(a.toString(), b.toString(),
            "fixture precondition: these must share an abbreviated toString");
        assertNotEquals(a.toJSON(), b.toJSON());
        assertAntisymmetric(a, b);
    }

    @Test
    void orderingIsAntisymmetricForDistinctHistogramsAndDirichlets() {
        assertAntisymmetric(histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75}),
            histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.75, 0.25}));
        assertAntisymmetric(dirichlet(2.0, 3.0), dirichlet(3.0, 2.0));
    }

    @Test
    void orderingIsAntisymmetricEvenWhenHashCodesCollide() {
        // Force the tie-break to be the only thing separating the operands.
        Object a = new CollidingKey("alpha");
        Object b = new CollidingKey("beta");
        assertEquals(a.hashCode(), b.hashCode(), "fixture precondition: hashes must collide");
        assertAntisymmetric(a, b);
    }

    @Test
    void equalOperandsMayOrderEitherWay() {
        // Identical content means the two orders yield identical results, so the
        // relation is allowed to be reflexive here.
        GMMValue a = gaussian(1.0, 2.0);
        GMMValue b = gaussian(1.0, 2.0);
        assertTrue(DistributionSeeds.inCanonicalOrder(a, b));
        assertTrue(DistributionSeeds.inCanonicalOrder(b, a));
    }

    @Test
    void orderingIsStableAcrossRepeatedCalls() {
        GMMValue a = gaussian(0.0, 1.0);
        GMMValue b = gaussian(5.0, 2.0);
        boolean first = DistributionSeeds.inCanonicalOrder(a, b);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, DistributionSeeds.inCanonicalOrder(a, b));
        }
    }

    @Test
    void orderingIsTransitive() {
        // A relation that is not a total order could cycle and defeat the guarantee.
        GMMValue[] values = {
            gaussian(0.0, 1.0), gaussian(1.0, 1.0), gaussian(2.0, 1.0),
            gaussian(3.0, 1.0), gaussian(4.0, 1.0)
        };
        for (GMMValue x : values) {
            for (GMMValue y : values) {
                for (GMMValue z : values) {
                    if (DistributionSeeds.inCanonicalOrder(x, y)
                        && DistributionSeeds.inCanonicalOrder(y, z)) {
                        assertTrue(DistributionSeeds.inCanonicalOrder(x, z),
                            "ordering must be transitive: " + x + " " + y + " " + z);
                    }
                }
            }
        }
    }

    @Test
    void distributionsCarryCompleteAndStableStringForms() {
        // inCanonicalOrder falls back to toString() for types it does not know about,
        // so a distribution whose toString is an identity hash would make the ordering
        // vary between JVM runs.
        HistogramValue h = histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.25, 0.75});
        DirichletValue d = dirichlet(2.0, 3.0);
        for (Object value : new Object[]{h, d}) {
            String text = value.toString();
            assertFalse(text.matches(".*@[0-9a-f]+$"),
                "toString must not be the identity-hash default: " + text);
            assertEquals(text, value.toString(), "toString must be stable");
        }
        // Both carry their parameters, so distinct values are distinguishable.
        assertNotEquals(h.toString(),
            histogram(new double[]{0.0, 1.0, 2.0}, new double[]{0.75, 0.25}).toString());
        assertNotEquals(d.toString(), dirichlet(3.0, 2.0).toString());
    }

    /** Two distinct values that deliberately collide on hashCode. */
    private static final class CollidingKey {
        private final String label;

        CollidingKey(String label) {
            this.label = label;
        }

        @Override
        public int hashCode() {
            return 42;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof CollidingKey other && label.equals(other.label);
        }

        @Override
        public String toString() {
            return "CollidingKey{" + label + "}";
        }
    }
}
