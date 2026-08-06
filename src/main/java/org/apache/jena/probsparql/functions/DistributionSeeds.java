package org.apache.jena.probsparql.functions;

import org.apache.jena.probsparql.datatypes.DirichletValue;
import org.apache.jena.probsparql.datatypes.GMMValue;

import java.util.Random;

/**
 * Derivation of deterministic random seeds from a function's operands.
 *
 * <p>Sampling-based SPARQL functions must be referentially transparent: evaluating
 * {@code prob:jsd(?a, ?b)} twice within one query, or re-running a benchmark, has to
 * produce the same number. A shared or ambient RNG breaks that — the value then
 * depends on how many other rows happened to be evaluated first, and under a
 * concurrent server on which thread got there.</p>
 *
 * <p>Seeding from the operands' own content instead makes each pair's estimate a
 * pure function of its inputs. Deriving the seed from a hash of the operands (rather
 * than from a fixed constant) also keeps the estimates of different pairs
 * statistically independent, which a single fixed seed would not.</p>
 *
 * <p>For symmetric functions the seed must not depend on argument order, so
 * {@link #forPair} sorts the two hashes before mixing.</p>
 */
public final class DistributionSeeds {

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_A = 0xBF58476D1CE4E5B9L;
    private static final long MIX_B = 0x94D049BB133111EBL;

    private DistributionSeeds() {
    }

    /**
     * Order-independent seed for a symmetric two-operand function.
     */
    public static long forPair(Object left, Object right) {
        int h1 = left == null ? 0 : left.hashCode();
        int h2 = right == null ? 0 : right.hashCode();
        long a = Integer.toUnsignedLong(Math.min(h1, h2));
        long b = Integer.toUnsignedLong(Math.max(h1, h2));
        return GOLDEN ^ (a * MIX_A) ^ (b * MIX_B);
    }

    /**
     * Order-dependent seed, for asymmetric functions such as KL divergence where
     * D(P‖Q) and D(Q‖P) are genuinely different quantities and should not share a
     * random stream.
     */
    public static long forOrderedPair(Object first, Object second) {
        long a = Integer.toUnsignedLong(first == null ? 0 : first.hashCode());
        long b = Integer.toUnsignedLong(second == null ? 0 : second.hashCode());
        return GOLDEN ^ (a * MIX_A) ^ Long.rotateLeft(b * MIX_B, 32);
    }

    /**
     * A fresh RNG seeded by {@link #forPair}.
     */
    public static Random rngForPair(Object left, Object right) {
        return new Random(forPair(left, right));
    }

    /**
     * Deterministic total order on two operands of a symmetric function.
     *
     * <p>An order-independent seed is not by itself enough to make a symmetric
     * estimator symmetric: the operands still consume the shared random stream in call
     * order, so swapping the arguments would draw different samples and return a
     * different realisation of the same quantity. Estimators therefore put their
     * operands in this order before sampling, which makes {@code f(a,b)} and
     * {@code f(b,a)} bit-identical.</p>
     *
     * <p>For that to hold the relation must be antisymmetric on distinct operands:
     * if both {@code inCanonicalOrder(a,b)} and {@code inCanonicalOrder(b,a)} were
     * true, neither call would swap and the operand order would again follow the
     * argument order. Hash codes decide the common case; on collision the tie is
     * broken by {@link #orderingKey}, which returns a <em>complete</em> lexical form.
     * A tie there therefore means the operands carry identical content, in which case
     * the two orders produce identical results and either answer is correct.</p>
     *
     * @return true if {@code left} should be processed first
     */
    public static boolean inCanonicalOrder(Object left, Object right) {
        int h1 = left == null ? 0 : left.hashCode();
        int h2 = right == null ? 0 : right.hashCode();
        if (h1 != h2) {
            return h1 <= h2;
        }
        return orderingKey(left).compareTo(orderingKey(right)) <= 0;
    }

    /**
     * Tie-breaking key for {@link #inCanonicalOrder}.
     *
     * <p>Must distinguish any two operands that are not interchangeable, so it uses
     * each distribution's full serialised form rather than {@code toString()}.
     * {@code GMMValue.toString()} in particular is an abbreviated summary — it reports
     * only the component count, dimensionality and covariance type — so two entirely
     * different mixtures share it, and using it here would collapse the tie-break for
     * every colliding GMM pair.</p>
     *
     * <p>A type reaching the fallback must have a {@code toString()} that is both
     * complete and stable across JVM runs; an identity-hash default would make the
     * ordering vary between runs and defeat the reproducibility this exists for.</p>
     */
    private static String orderingKey(Object value) {
        if (value instanceof GMMValue gmm) {
            return gmm.toJSON();
        }
        if (value instanceof DirichletValue dirichlet) {
            return dirichlet.toJSON();
        }
        // HistogramValue.toString() is its complete JSON lexical form.
        return String.valueOf(value);
    }

    /**
     * A fresh RNG seeded by {@link #forOrderedPair}.
     */
    public static Random rngForOrderedPair(Object first, Object second) {
        return new Random(forOrderedPair(first, second));
    }
}
