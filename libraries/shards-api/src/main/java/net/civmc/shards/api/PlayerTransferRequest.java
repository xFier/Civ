package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy to move a player to whichever shard owns {@code targetLocation}.
 *
 * <p>The sending server does not name a destination. It says where the player is going and the proxy
 * resolves which shard that is, so the shard map stays authoritative in one process and a server
 * cannot send someone somewhere that no longer exists.</p>
 *
 * @param payload base64 of the player's snapshot, written as part of handing ownership over
 */
public record PlayerTransferRequest(UUID requestId, String serverName, UUID playerUuid, String payload,
                                    PlayerLocation targetLocation, long createdAtEpochMillis) {

    public PlayerTransferRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(payload, "payload");
        Objects.requireNonNull(targetLocation, "targetLocation");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerTransferRequest create(final String serverName, final UUID playerUuid,
                                               final String payload, final PlayerLocation targetLocation) {
        return new PlayerTransferRequest(UUID.randomUUID(), serverName, playerUuid, payload, targetLocation,
            System.currentTimeMillis());
    }
}
