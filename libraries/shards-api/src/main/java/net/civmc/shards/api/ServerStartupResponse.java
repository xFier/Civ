package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.civmc.shards.api.region.ShardRegion;

/**
 * The proxy's answer to a {@link ServerStartupRequest}.
 *
 * <p>Carries the requesting server's own areas as well as the lock count, so startup is one round
 * trip. A server is given only its own regions: it needs them to know when a player has left, and the
 * proxy resolves where a leaving player is going, so the rest of the map is not its business.</p>
 *
 * @param releasedLockCount how many stale locks were cleared, so the requesting server can report what
 *     actually happened rather than only that nothing threw
 * @param regions the areas this server owns, empty if it is not a shard
 */
public record ServerStartupResponse(UUID requestId, boolean success, String failureMessage,
                                    int releasedLockCount, List<ShardRegion> regions,
                                    long completedAtEpochMillis) {

    public ServerStartupResponse {
        Objects.requireNonNull(requestId, "requestId");
        if (success && failureMessage != null && !failureMessage.isEmpty()) {
            throw new IllegalArgumentException("successful responses must not carry a failureMessage");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (!success && failureMessage.isEmpty()) {
            throw new IllegalArgumentException("failed responses must carry a failureMessage");
        }
        if (releasedLockCount < 0) {
            throw new IllegalArgumentException("releasedLockCount must not be negative");
        }
        regions = regions == null ? List.of() : List.copyOf(regions);
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static ServerStartupResponse success(final UUID requestId, final int releasedLockCount,
                                                final List<ShardRegion> regions) {
        return new ServerStartupResponse(requestId, true, "", releasedLockCount, regions,
            System.currentTimeMillis());
    }

    public static ServerStartupResponse failure(final UUID requestId, final String failureMessage) {
        return new ServerStartupResponse(requestId, false, failureMessage, 0, List.of(),
            System.currentTimeMillis());
    }
}
