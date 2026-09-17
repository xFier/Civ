package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy what the sky should look like.
 *
 * <p>Asked for rather than pushed out. A shard that has just started, or has just got its connection
 * back, needs the answer immediately and would otherwise spend whatever is left of a broadcast
 * interval showing the wrong sky. Asking means a shard is right within one interval of joining, and
 * that nothing has to keep track of who is listening.</p>
 */
public record SkyStateRequest(UUID requestId, String serverName, long createdAtEpochMillis) {

    public SkyStateRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static SkyStateRequest create(final String serverName) {
        return new SkyStateRequest(UUID.randomUUID(), serverName, System.currentTimeMillis());
    }
}
