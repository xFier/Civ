package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the shard that owns a chunk what is really in it.
 *
 * <p>The first message in this project that goes shard to shard rather than through the proxy. It has
 * to: the proxy holds the shard map and the player data, but it does not hold a world, so it cannot
 * answer this. The proxy still decides <em>who</em> owns the chunk - that is asked separately, with
 * the border probe that already exists.</p>
 *
 * <p>Asked on demand, when a player comes near a chunk this server does not own, and the answer is
 * cached and shared by everybody on this server. There is deliberately no catch-up at startup and no
 * log of changes to replay: a chunk fetched now is current by construction, where a replayed log is
 * only as good as the last time anyone was listening.</p>
 */
public record ChunkStateRequest(UUID requestId, String serverName, String world, int chunkX, int chunkZ,
                                long createdAtEpochMillis) {

    public ChunkStateRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static ChunkStateRequest create(final String serverName, final String world, final int chunkX,
                                           final int chunkZ) {
        return new ChunkStateRequest(UUID.randomUUID(), serverName, world, chunkX, chunkZ,
            System.currentTimeMillis());
    }
}
