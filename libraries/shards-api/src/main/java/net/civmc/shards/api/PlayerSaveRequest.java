package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Hands a player's state back to the proxy and gives up ownership of it.
 *
 * @param payload base64 of the snapshot
 * @param location where the player was, which is what decides the shard they belong to next time
 */
public record PlayerSaveRequest(UUID requestId, String serverName, UUID playerUuid, String payload,
                                PlayerLocation location, long createdAtEpochMillis) {

    public PlayerSaveRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(payload, "payload");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerSaveRequest create(final String serverName, final UUID playerUuid, final String payload,
                                           final PlayerLocation location) {
        return new PlayerSaveRequest(UUID.randomUUID(), serverName, playerUuid, payload, location,
            System.currentTimeMillis());
    }
}
