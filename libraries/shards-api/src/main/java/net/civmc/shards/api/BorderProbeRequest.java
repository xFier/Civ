package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.civmc.shards.api.region.ShardPoint;

/**
 * Asks the proxy what owns each of a handful of blocks, without moving anybody.
 *
 * <p>Separate from a transfer on purpose: this is asked while a player is merely walking near an
 * edge, so it must never write anything, never take a lock and never be mistaken for an attempt to
 * cross. The proxy answers it from the same shard map that resolves a real crossing, so what a player
 * is shown at a border and what happens when they step over it cannot disagree.</p>
 *
 * <p>Several blocks per request rather than one, because a border is not a single place. What is
 * beyond the edge changes along it - a doorway can sit next to a wall, and a shard's outline can turn
 * a corner or be notched out by ground swapped to its neighbour - so drawing it truthfully means
 * knowing every face in sight, and asking for those one at a time would be a round trip per block.</p>
 *
 * <p>No world is named. Shards divide the x/z plane rather than the world list, so every world at a
 * coordinate belongs to the same shard.</p>
 */
public record BorderProbeRequest(UUID requestId, String serverName, List<ShardPoint> blocks,
                                 long createdAtEpochMillis) {

    /**
     * Bounded so one probe cannot ask the proxy to walk the shard map thousands of times. Comfortably
     * more faces than fit in the distance a border is drawn from.
     */
    public static final int MAX_BLOCKS = 128;

    public BorderProbeRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(blocks, "blocks");
        if (blocks.isEmpty()) {
            throw new IllegalArgumentException("A border probe must ask about at least one block");
        }
        if (blocks.size() > MAX_BLOCKS) {
            throw new IllegalArgumentException("A border probe may ask about at most " + MAX_BLOCKS
                + " blocks, not " + blocks.size());
        }
        blocks = List.copyOf(blocks);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static BorderProbeRequest create(final String serverName, final List<ShardPoint> blocks) {
        return new BorderProbeRequest(UUID.randomUUID(), serverName, blocks, System.currentTimeMillis());
    }
}
