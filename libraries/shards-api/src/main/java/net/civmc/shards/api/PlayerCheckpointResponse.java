package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param status {@link SaveStatus#SAVED} when written. Anything else means this server no longer owns
 *     the player it thinks it is serving, which is worth knowing about immediately
 */
public record PlayerCheckpointResponse(UUID requestId, SaveStatus status, UUID heldBy, String failureMessage,
                                       long completedAtEpochMillis) {

    public PlayerCheckpointResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerCheckpointResponse of(final UUID requestId, final SaveStatus status, final UUID heldBy) {
        return new PlayerCheckpointResponse(requestId, status, heldBy, "", System.currentTimeMillis());
    }

    public static PlayerCheckpointResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerCheckpointResponse(requestId, SaveStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
