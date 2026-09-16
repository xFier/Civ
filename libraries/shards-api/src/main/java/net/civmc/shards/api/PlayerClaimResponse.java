package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param payload base64 of the stored snapshot, null unless the status is {@link ClaimStatus#LOADED}
 *     with data written back at least once
 * @param location where the player was when last written back, null if never
 * @param heldBy the server holding the lock, only when the status is {@link ClaimStatus#HELD_BY_OTHER}
 */
public record PlayerClaimResponse(UUID requestId, ClaimStatus status, String payload, PlayerLocation location,
                                  UUID heldBy, String failureMessage, long completedAtEpochMillis) {

    public PlayerClaimResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerClaimResponse loaded(final UUID requestId, final String payload,
                                             final PlayerLocation location) {
        return new PlayerClaimResponse(requestId, ClaimStatus.LOADED, payload, location, null, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse newPlayer(final UUID requestId) {
        return new PlayerClaimResponse(requestId, ClaimStatus.NEW_PLAYER, null, null, null, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse heldByOther(final UUID requestId, final UUID heldBy) {
        return new PlayerClaimResponse(requestId, ClaimStatus.HELD_BY_OTHER, null, null, heldBy, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerClaimResponse(requestId, ClaimStatus.ERROR, null, null, null, failureMessage,
            System.currentTimeMillis());
    }
}
