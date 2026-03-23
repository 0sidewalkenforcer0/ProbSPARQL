package org.apache.jena.probsparql.exp2;

/**
 * Thread-local exchange point for PruningStats.
 *
 * {@link org.apache.jena.sparql.engine.iterator.QueryIterPrunedSimilarityJoin}
 * writes its final stats here when close() is called, so that
 * {@code Exp2Benchmark.collectPruningStats()} can retrieve them.
 */
public final class Exp2PruningHolder {

    private static final ThreadLocal<PruningStats> SLOT = new ThreadLocal<>();

    private Exp2PruningHolder() {}

    public static void set(PruningStats stats) {
        SLOT.set(stats);
    }

    /** Returns the most recently stored stats, or {@code null} if none. */
    public static PruningStats get() {
        return SLOT.get();
    }

    public static void clear() {
        SLOT.remove();
    }
}
