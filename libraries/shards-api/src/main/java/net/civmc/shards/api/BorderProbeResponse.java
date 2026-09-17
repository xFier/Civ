package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * @param results one per block asked about, in no particular order
 * @param failureMessage why nothing could be answered, blank when the probe succeeded
 */
public record BorderProbeResponse(UUID requestId, List<BorderProbeResult> results, String failureMessage,
                                  long completedAtEpochMillis) {

    public BorderProbeResponse {
        Objects.requireNonNull(requestId, "requestId");
        results = results == null ? List.of() : List.copyOf(results);
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static BorderProbeResponse of(final UUID requestId, final List<BorderProbeResult> results) {
        return new BorderProbeResponse(requestId, results, "", System.currentTimeMillis());
    }

    public static BorderProbeResponse error(final UUID requestId, final String failureMessage) {
        return new BorderProbeResponse(requestId, List.of(), failureMessage, System.currentTimeMillis());
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty();
    }
}
