package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy to take ownership of a player's data for the requesting server.
 *
 * <p>Sent while the player is still connecting, so the answer decides whether the login is allowed at
 * all.</p>
 */
public record PlayerClaimRequest(UUID requestId, String serverName, UUID playerUuid,
                                 long createdAtEpochMillis) {

    public PlayerClaimRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerClaimRequest create(final String serverName, final UUID playerUuid) {
        return new PlayerClaimRequest(UUID.randomUUID(), serverName, playerUuid, System.currentTimeMillis());
    }
}
