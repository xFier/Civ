package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param destinationServer where the player was sent, only when the status is
 *     {@link TransferStatus#TRANSFERRED}
 */
public record PlayerTransferResponse(UUID requestId, TransferStatus status, String destinationServer,
                                     String failureMessage, long completedAtEpochMillis) {

    public PlayerTransferResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static PlayerTransferResponse transferred(final UUID requestId, final String destinationServer) {
        return new PlayerTransferResponse(requestId, TransferStatus.TRANSFERRED, destinationServer, "",
            System.currentTimeMillis());
    }

    public static PlayerTransferResponse of(final UUID requestId, final TransferStatus status,
                                            final String failureMessage) {
        return new PlayerTransferResponse(requestId, status, null, failureMessage, System.currentTimeMillis());
    }

    public static PlayerTransferResponse error(final UUID requestId, final String failureMessage) {
        return new PlayerTransferResponse(requestId, TransferStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
