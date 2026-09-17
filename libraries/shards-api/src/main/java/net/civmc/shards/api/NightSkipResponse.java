package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * @param sky the sky as it stands now, so the shard that asked can show morning without waiting for
 *     its next poll - the people who just slept are the ones most likely to notice a delay
 * @param skipped whether the night was actually moved on. False once it is already morning, which is
 *     not an error: two shards can sleep at the same time and only one of them can be the one that
 *     did it
 * @param failureMessage why nothing could be answered, blank when it could
 */
public record NightSkipResponse(UUID requestId, SkyState sky, boolean skipped, String failureMessage,
                                long completedAtEpochMillis) {

    public NightSkipResponse {
        Objects.requireNonNull(requestId, "requestId");
        failureMessage = failureMessage == null ? "" : failureMessage;
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static NightSkipResponse of(final UUID requestId, final SkyState sky, final boolean skipped) {
        return new NightSkipResponse(requestId, sky, skipped, "", System.currentTimeMillis());
    }

    public static NightSkipResponse error(final UUID requestId, final String failureMessage) {
        return new NightSkipResponse(requestId, null, false, failureMessage, System.currentTimeMillis());
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty() || this.sky == null;
    }
}
