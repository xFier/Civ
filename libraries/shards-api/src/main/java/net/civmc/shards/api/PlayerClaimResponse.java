package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param payload base64 of the stored snapshot, null unless the status is {@link ClaimStatus#LOADED}
 *     with data written back at least once
 * @param location where the player was when last written back, null if never
 * @param heldBy the server holding the lock, only when the status is {@link ClaimStatus#HELD_BY_OTHER}
 * @param arriving whether this player is crossing in from another shard rather than logging in. Only
 *     the proxy can tell the two apart, because only the proxy starts a handover - and the difference
 *     is invisible from the destination, where both claim a lock and restore a stored snapshot. An
 *     older proxy simply leaves this false, which reads as a login
 */
public record PlayerClaimResponse(UUID requestId, ClaimStatus status, String payload, PlayerLocation location,
                                  UUID heldBy, boolean arriving, String failureMessage,
                                  long completedAtEpochMillis) {

    public PlayerClaimResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerClaimResponse loaded(final UUID requestId, final String payload,
                                             final PlayerLocation location, final boolean arriving) {
        return new PlayerClaimResponse(requestId, ClaimStatus.LOADED, payload, location, null, arriving, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse newPlayer(final UUID requestId) {
        return new PlayerClaimResponse(requestId, ClaimStatus.NEW_PLAYER, null, null, null, false, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse heldByOther(final UUID requestId, final UUID heldBy) {
        return new PlayerClaimResponse(requestId, ClaimStatus.HELD_BY_OTHER, null, null, heldBy, false, "",
            System.currentTimeMillis());
    }

    public static PlayerClaimResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerClaimResponse(requestId, ClaimStatus.ERROR, null, null, null, false, failureMessage,
            System.currentTimeMillis());
    }
}
