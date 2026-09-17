package net.civmc.shards.paper.mirror;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How many times each of this shard's chunks has been announced as changed.
 *
 * <p>The announcements are the only way a viewer learns about a change without re-reading a chunk, and
 * they go out on a fanout with no acknowledgement of any kind. One that never arrives leaves a viewer
 * showing the wrong blocks until the ten-minute backstop, and a viewer has no way to tell that from a
 * neighbour where nothing has happened. Numbering them makes the difference visible: a viewer that
 * receives {@code 7} when it was expecting {@code 6} knows it has missed one, and reads the chunk
 * again.</p>
 *
 * <p><strong>This is not a log and nothing is replayed.</strong> The number says only <em>that</em>
 * something was missed, never what - the answer to a gap is a full read, which is current by
 * construction. A log would have to be kept, trimmed, and trusted to be complete; a counter has to be
 * neither.</p>
 *
 * <p>A chunk read carries the number in force when its snapshot was taken, which is what makes the
 * two halves fit: a snapshot at {@code 5} plus every announcement above {@code 5} is the chunk as it
 * is now. The counter is only ever advanced by the once-a-tick flush and only ever read on the main
 * thread, so no announcement can be numbered between a snapshot and the number sent with it.</p>
 *
 * <p>The counts start again from nothing when this server restarts, which would look to a viewer like
 * numbers running backwards. Hence {@link #publisherId()}: a viewer that sees it change knows the
 * numbering has restarted and reads the chunk again rather than trying to reconcile the two.</p>
 */
public final class ChunkRevisions {

    // New every time the plugin is enabled, on purpose. It is the thing that distinguishes "you have
    // missed an announcement" from "the shard you are watching has restarted"
    private final String publisherId = UUID.randomUUID().toString();
    // Only chunks that have actually changed, and only those near a border, because only those are
    // ever announced. A chunk nobody has touched answers nothing rather than holding a zero
    private final Map<ChunkKey, Long> revisions = new ConcurrentHashMap<>();

    /**
     * Which run of which server these numbers belong to.
     */
    public String publisherId() {
        return this.publisherId;
    }

    /**
     * The number for an announcement about to go out.
     */
    public long next(final ChunkKey chunk) {
        return this.revisions.merge(chunk, 1L, Long::sum);
    }

    /**
     * The same, but deliberately leaving a hole, for an announcement that is known not to describe
     * everything that changed.
     *
     * <p>Used when more blocks changed in one tick than will be published. Sending the incomplete
     * announcement under the next number would have every viewer accept it as the whole story; under a
     * number with a gap in front of it, every viewer reads the chunk instead, which is the only answer
     * that is right.</p>
     */
    public long nextWithGap(final ChunkKey chunk) {
        return this.revisions.merge(chunk, 2L, Long::sum);
    }

    /**
     * The number in force now, for sending with a snapshot. Does not advance anything.
     */
    public long current(final ChunkKey chunk) {
        return this.revisions.getOrDefault(chunk, 0L);
    }
}
