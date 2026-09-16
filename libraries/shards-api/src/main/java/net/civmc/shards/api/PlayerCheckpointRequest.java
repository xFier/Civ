package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Writes a player's state back <strong>without giving up ownership</strong>.
 *
 * <p>For a player who is still playing. Data is otherwise only written when someone leaves or is handed
 * to another shard, so a server that is killed rather than stopped loses everything since they arrived
 * - they wake up wherever they last crossed a border, with whatever they had then.</p>
 *
 * <p>Deliberately not {@link PlayerSaveRequest} with a flag: releasing ownership while the player is
 * still on the server is the one thing this must never do, and a boolean is a poor guard against it.</p>
 */
public record PlayerCheckpointRequest(UUID requestId, String serverName, UUID playerUuid, String payload,
                                      PlayerLocation location, long createdAtEpochMillis) {

    public PlayerCheckpointRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(payload, "payload");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerCheckpointRequest create(final String serverName, final UUID playerUuid,
                                                 final String payload, final PlayerLocation location) {
        return new PlayerCheckpointRequest(UUID.randomUUID(), serverName, playerUuid, payload, location,
            System.currentTimeMillis());
    }
}
