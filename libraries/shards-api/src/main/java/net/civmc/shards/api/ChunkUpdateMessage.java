package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import net.civmc.shards.api.mirror.BlockUpdate;

/**
 * Blocks that have just changed on one shard, told to everybody rather than asked for.
 *
 * <p>The only message here with no reply and no addressee. It goes to a fanout exchange and every
 * shard gets a copy, which is deliberate: a shard would otherwise have to know which of its
 * neighbours is looking at which of its chunks, and keep that up to date as players walk. Instead a
 * receiver drops anything about a chunk it has not fetched, which it can answer from its own cache
 * without asking anyone. Cheap, because only blocks near a border are published at all.</p>
 *
 * <p>This is what stops the mirror re-reading a whole band of chunks every minute to discover that
 * nothing has changed. Measured on the rig before it existed: seventy chunks re-read every thirty
 * seconds for a player who was standing still, to find nothing.</p>
 *
 * <p>One message per chunk, so a receiver can decide whether it cares before reading any blocks.</p>
 */
public record ChunkUpdateMessage(String serverName, String world, int chunkX, int chunkZ,
                                 List<BlockUpdate> updates, long createdAtEpochMillis) {

    public ChunkUpdateMessage {
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Objects.requireNonNull(updates, "updates");
        updates = List.copyOf(updates);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static ChunkUpdateMessage create(final String serverName, final String world, final int chunkX,
                                            final int chunkZ, final List<BlockUpdate> updates) {
        return new ChunkUpdateMessage(serverName, world, chunkX, chunkZ, updates, System.currentTimeMillis());
    }
}
