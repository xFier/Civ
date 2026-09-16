package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Sent by a server once it has started, to have the locks its predecessor left behind released.
 *
 * <p>Only ever sent by a server that has just enabled and therefore has nobody online. Sending it at
 * any other time would release the locks of the players currently being served.</p>
 */
public record ServerStartupRequest(UUID requestId, String serverName, long createdAtEpochMillis) {

    public ServerStartupRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = requireNonBlank(serverName, "serverName");
        if (createdAtEpochMillis <= 0) {
            throw new IllegalArgumentException("createdAtEpochMillis must be positive");
        }
    }

    public static ServerStartupRequest create(final String serverName) {
        return new ServerStartupRequest(UUID.randomUUID(), serverName, System.currentTimeMillis());
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
