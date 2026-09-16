package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param shardName who owns the place asked about, only when the status is
 *     {@link BorderProbeStatus#CROSSABLE} or {@link BorderProbeStatus#UNREACHABLE}
 */
public record BorderProbeResponse(UUID requestId, BorderProbeStatus status, String shardName,
                                  String failureMessage, long completedAtEpochMillis) {

    public BorderProbeResponse {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(status, "status");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static BorderProbeResponse of(final UUID requestId, final BorderProbeStatus status,
                                         final String shardName) {
        return new BorderProbeResponse(requestId, status, shardName, "", System.currentTimeMillis());
    }

    public static BorderProbeResponse error(final UUID requestId, final String failureMessage) {
        return new BorderProbeResponse(requestId, BorderProbeStatus.ERROR, null, failureMessage,
            System.currentTimeMillis());
    }
}
