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
    private final AtomicLong waitNanos = new AtomicLong();
    private final AtomicLong buildNanos = new AtomicLong();
    private final AtomicLong encodeNanos = new AtomicLong();
    private final AtomicLong servedBytes = new AtomicLong();
    private final AtomicLong worstWaitNanos = new AtomicLong();
    private final AtomicLong worstBuildNanos = new AtomicLong();

    private final AtomicLong diffed = new AtomicLong();
    private final AtomicLong diffedNanos = new AtomicLong();
    private final AtomicLong changedBlocks = new AtomicLong();
    private final AtomicLong diffedWorstNanos = new AtomicLong();
    private final AtomicLong worstChangedBlocks = new AtomicLong();

    private final AtomicLong missedAnnouncements = new AtomicLong();

    /**
     * One chunk read and encoded for a neighbour. Called on whichever thread did the work.
     *
     * <p>Split three ways on purpose. <strong>Waiting</strong> is the chunk coming off disk and then a
     * tick arriving to take the snapshot on - latency, spent idle, and it decides how long a border
     * takes to fill in. <strong>Building</strong> and <strong>encoding</strong> are work actually
     * done, off the main thread, and they are what a busy border would cost. Reported as one number
     * they are indistinguishable, which made a mirror that is merely slow look like an expensive
     * one.</p>
     */
    public void served(final long waited, final long built, final long encoded, final int bytes) {
        this.served.incrementAndGet();
        this.waitNanos.addAndGet(waited);
        this.buildNanos.addAndGet(built);
        this.encodeNanos.addAndGet(encoded);
        this.servedBytes.addAndGet(bytes);
        this.worstWaitNanos.accumulateAndGet(waited, Math::max);
        this.worstBuildNanos.accumulateAndGet(built, Math::max);
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
     * One announcement that never arrived, noticed because the next one did not follow on from the
     * last. Worth counting on its own: the fanout has no acknowledgement of any kind, so before the
     * numbering there was no way to know whether this was happening at all, and the answer decides
     * whether the mirror can be trusted without re-reading. A restart of a neighbour counts here too,
     * which is the one benign cause.
     */
    public void missedAnnouncement() {
        this.missedAnnouncements.incrementAndGet();
    }

    /**
     * Says what has happened since the last time it was asked, and forgets it. Silent when nothing
     * has - a quiet border should not be writing to the log every half minute.
     */
    public void report(final Logger logger) {
        final long servedCount = this.served.getAndSet(0L);
        if (servedCount > 0L) {
            logger.info(String.format(
                "Mirror served %d chunk(s) to neighbours, each averaging %.1fms waiting (worst %.1f), "
                    + "%.1fms building (worst %.1f), %.1fms encoding, %.1fKB",
                servedCount,
                millis(this.waitNanos.getAndSet(0L) / servedCount),
                millis(this.worstWaitNanos.getAndSet(0L)),
                millis(this.buildNanos.getAndSet(0L) / servedCount),
                millis(this.worstBuildNanos.getAndSet(0L)),
                millis(this.encodeNanos.getAndSet(0L) / servedCount),
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
        final long missed = this.missedAnnouncements.getAndSet(0L);
        if (missed > 0L) {
            logger.info("Mirror missed " + missed + " block announcement(s) and read those chunk(s) "
                + "again; a neighbour restarting counts here too");
        }
    }

    private static double millis(final long nanos) {
        return nanos / 1_000_000.0;
    }
}
