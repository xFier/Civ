package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

public record PlayerSaveResponse(UUID requestId, SaveStatus status, UUID heldBy, String failureMessage,
                                 long completedAtEpochMillis) {

    public PlayerSaveResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerSaveResponse of(final UUID requestId, final SaveStatus status, final UUID heldBy) {
        return new PlayerSaveResponse(requestId, status, heldBy, "", System.currentTimeMillis());
    }

    public static PlayerSaveResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerSaveResponse(requestId, SaveStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
