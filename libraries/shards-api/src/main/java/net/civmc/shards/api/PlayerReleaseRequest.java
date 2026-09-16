package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Gives up ownership of a player <strong>without writing anything back</strong>.
 *
 * <p>For a login that was allowed and then never completed: the lock was taken before the player
 * existed, so there is nothing to read state from. Sending a save with an empty payload instead would
 * destroy the very data the lock was protecting.</p>
 */
public record PlayerReleaseRequest(UUID requestId, String serverName, UUID playerUuid,
                                   long createdAtEpochMillis) {

    public PlayerReleaseRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerReleaseRequest create(final String serverName, final UUID playerUuid) {
        return new PlayerReleaseRequest(UUID.randomUUID(), serverName, playerUuid, System.currentTimeMillis());
    }
}
