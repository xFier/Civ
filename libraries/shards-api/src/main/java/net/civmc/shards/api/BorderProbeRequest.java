package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Asks the proxy what owns a place, without moving anybody.
 *
 * <p>Separate from a transfer on purpose: this is asked while a player is merely walking near an
 * edge, so it must never write anything, never take a lock and never be mistaken for an attempt to
 * cross. The proxy answers it from the same shard map that resolves a real crossing, so what a player
 * is shown at a border and what happens when they step over it cannot disagree.</p>
 */
public record BorderProbeRequest(UUID requestId, String serverName, PlayerLocation location,
                                 long createdAtEpochMillis) {

    public BorderProbeRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Objects.requireNonNull(location, "location");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static BorderProbeRequest create(final String serverName, final PlayerLocation location) {
        return new BorderProbeRequest(UUID.randomUUID(), serverName, location, System.currentTimeMillis());
    }
}
