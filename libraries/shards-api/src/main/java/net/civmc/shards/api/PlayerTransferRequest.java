package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy to move a player to another shard.
 *
 * <p>Addressed one of two ways, and exactly one must be given:</p>
 *
 * <ul>
 *   <li>by <strong>location</strong>, for a move to a particular place - walking over a border, or a
 *       rocket landing at its pad. The sending server does not work out which shard that is; the
 *       proxy does, so the shard map stays authoritative in one process and a server cannot send
 *       someone somewhere it has stale knowledge of.</li>
 *   <li>by <strong>shard</strong>, for a move where the destination decides where the player appears -
 *       arriving somewhere for the first time, where that server's own spawn logic places them. There
 *       is no sensible coordinate for the sender to name, and inventing one here would put the
 *       arrival point in the wrong plugin.</li>
 * </ul>
 *
 * @param payload base64 of the player's snapshot, written as part of handing ownership over. It is
 *     carried faithfully: anything that should not travel is taken off the player before the snapshot
 *     is captured, so that stays a rule of whatever is moving them rather than something transport
 *     does on its own
 * @param targetLocation where the player is going, or null when addressed by shard
 * @param targetShard the destination server's name, or null when addressed by location
 */
public record PlayerTransferRequest(UUID requestId, String serverName, UUID playerUuid, String payload,
                                    PlayerLocation targetLocation, String targetShard,
                                    long createdAtEpochMillis) {

    public PlayerTransferRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(playerUuid, "playerUuid");
        Objects.requireNonNull(payload, "payload");
        targetShard = targetShard == null || targetShard.isBlank() ? null : targetShard.trim();
        // Both would be ambiguous and neither has nowhere to send them, so either is a bug in the
        // caller rather than something to resolve by preferring one
        if (targetLocation == null && targetShard == null) {
            throw new IllegalArgumentException("a transfer needs either a targetLocation or a targetShard");
        }
        if (targetLocation != null && targetShard != null) {
            throw new IllegalArgumentException("a transfer cannot have both a targetLocation and a targetShard");
        }
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerTransferRequest toLocation(final String serverName, final UUID playerUuid,
                                                   final String payload, final PlayerLocation targetLocation) {
        return new PlayerTransferRequest(UUID.randomUUID(), serverName, playerUuid, payload,
            Objects.requireNonNull(targetLocation, "targetLocation"), null, System.currentTimeMillis());
    }

    public static PlayerTransferRequest toShard(final String serverName, final UUID playerUuid,
                                                final String payload, final String targetShard) {
        return new PlayerTransferRequest(UUID.randomUUID(), serverName, playerUuid, payload, null,
            Messages.requireNonBlank(targetShard, "targetShard"), System.currentTimeMillis());
    }
}
