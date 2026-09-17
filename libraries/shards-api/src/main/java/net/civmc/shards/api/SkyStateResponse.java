package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param sky what the shard should be showing, null when the proxy could not say
 * @param failureMessage why it could not, blank when it could
 */
public record SkyStateResponse(UUID requestId, SkyState sky, String failureMessage,
                               long completedAtEpochMillis) {

    public SkyStateResponse {
        Objects.requireNonNull(requestId, "requestId");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static SkyStateResponse of(final UUID requestId, final SkyState sky) {
        return new SkyStateResponse(requestId, sky, "", System.currentTimeMillis());
    }

    public static SkyStateResponse error(final UUID requestId, final String failureMessage) {
        return new SkyStateResponse(requestId, null, failureMessage, System.currentTimeMillis());
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty() || this.sky == null;
    }
}
