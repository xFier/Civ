package net.civmc.shards.paper.mirror;

import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * What the mirror is actually costing, because nothing else can tell you.
 *
 * <p>Two numbers decide whether the mirror is cheap or expensive, and neither is visible from the
 * outside. <strong>How long a chunk takes to read</strong> is paid by the shard being looked at, for
 * somebody else's screen, and it is the same cost whether the answer turns out to be interesting or
 * not - sixteen thousand blocks a section either way. <strong>How many blocks actually differ</strong>
 * decides whether this is a handful of block changes or a whole chunk every time, and it cannot be
 * inferred from anything on disk: two copies of the same world differ byte for byte the moment either
 * server ticks them, because the times played and the lighting move even when every block is the
 * same.</p>
 *
 * <p>Summarised rather than logged per chunk. A player walking along a border touches hundreds, and a
 * line each would bury the thing being measured in the measurement.</p>
 */
public final class MirrorMetrics {

    private final AtomicLong served = new AtomicLong();
    private final AtomicLong servedNanos = new AtomicLong();
    private final AtomicLong servedBytes = new AtomicLong();
    private final AtomicLong servedWorstNanos = new AtomicLong();

    private final AtomicLong diffed = new AtomicLong();
    private final AtomicLong diffedNanos = new AtomicLong();
    private final AtomicLong changedBlocks = new AtomicLong();
    private final AtomicLong diffedWorstNanos = new AtomicLong();
    private final AtomicLong worstChangedBlocks = new AtomicLong();

    /**
     * One chunk read and encoded for a neighbour. Called on whichever thread did the work.
     */
    public void served(final long nanos, final int bytes) {
        this.served.incrementAndGet();
        this.servedNanos.addAndGet(nanos);
        this.servedBytes.addAndGet(bytes);
        this.servedWorstNanos.accumulateAndGet(nanos, Math::max);
    }

    /**
     * One chunk compared against our own copy.
     *
     * @param changed how many blocks the neighbour has that we do not. The number that says whether
     *     the two copies are a handful of buildings apart or have nothing in common
     */
    public void diffed(final long nanos, final int changed) {
        this.diffed.incrementAndGet();
        this.diffedNanos.addAndGet(nanos);
        this.changedBlocks.addAndGet(changed);
        this.diffedWorstNanos.accumulateAndGet(nanos, Math::max);
        this.worstChangedBlocks.accumulateAndGet(changed, Math::max);
    }

    /**
     * Says what has happened since the last time it was asked, and forgets it. Silent when nothing
     * has - a quiet border should not be writing to the log every half minute.
     */
    public void report(final Logger logger) {
        final long servedCount = this.served.getAndSet(0L);
        if (servedCount > 0L) {
            logger.info(String.format(
                "Mirror served %d chunk(s) to neighbours: %.1fms each on average, worst %.1fms, %.1fKB each",
                servedCount,
                millis(this.servedNanos.getAndSet(0L) / servedCount),
                millis(this.servedWorstNanos.getAndSet(0L)),
                this.servedBytes.getAndSet(0L) / 1024.0 / servedCount));
        }
        final long diffedCount = this.diffed.getAndSet(0L);
        if (diffedCount > 0L) {
            logger.info(String.format(
                "Mirror compared %d chunk(s): %.1fms each on average, worst %.1fms, %d block(s) differ "
                    + "each on average, worst %d",
                diffedCount,
                millis(this.diffedNanos.getAndSet(0L) / diffedCount),
                millis(this.diffedWorstNanos.getAndSet(0L)),
                this.changedBlocks.getAndSet(0L) / diffedCount,
                this.worstChangedBlocks.getAndSet(0L)));
        }
    }

    private static double millis(final long nanos) {
        return nanos / 1_000_000.0;
    }
}
