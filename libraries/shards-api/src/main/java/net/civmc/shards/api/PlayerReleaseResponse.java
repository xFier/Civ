package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param released whether a lock held by the requesting server was actually dropped
 */
public record PlayerReleaseResponse(UUID requestId, boolean success, boolean released, String failureMessage,
                                    long completedAtEpochMillis) {

    public PlayerReleaseResponse {
        Objects.requireNonNull(requestId, "requestId");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerReleaseResponse success(final UUID requestId, final boolean released) {
        return new PlayerReleaseResponse(requestId, true, released, "", System.currentTimeMillis());
    }

    public static PlayerReleaseResponse failure(final UUID requestId, final String failureMessage) {
        return new PlayerReleaseResponse(requestId, false, false, failureMessage, System.currentTimeMillis());
    }
}
