package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * The proxy's answer to a {@link ServerStartupRequest}.
 *
 * @param releasedLockCount how many stale locks were cleared, so the requesting server can report
 *     what actually happened rather than only that nothing threw
 */
public record ServerStartupResponse(UUID requestId, boolean success, String failureMessage,
                                    int releasedLockCount, long completedAtEpochMillis) {

    public ServerStartupResponse {
        Objects.requireNonNull(requestId, "requestId");
        if (success && failureMessage != null && !failureMessage.isEmpty()) {
            throw new IllegalArgumentException("successful responses must not carry a failureMessage");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (!success && failureMessage.isEmpty()) {
            throw new IllegalArgumentException("failed responses must carry a failureMessage");
        }
        if (releasedLockCount < 0) {
            throw new IllegalArgumentException("releasedLockCount must not be negative");
        }
        if (completedAtEpochMillis <= 0) {
            throw new IllegalArgumentException("completedAtEpochMillis must be positive");
        }
    }

    public static ServerStartupResponse success(final UUID requestId, final int releasedLockCount) {
        return new ServerStartupResponse(requestId, true, "", releasedLockCount, System.currentTimeMillis());
    }

    public static ServerStartupResponse failure(final UUID requestId, final String failureMessage) {
        return new ServerStartupResponse(requestId, false, failureMessage, 0, System.currentTimeMillis());
    }
}
