package net.civmc.shards.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Says that the players on one shard have slept through the night, and asks for the network's clock
 * to be moved on.
 *
 * <p>Sleeping is the one thing besides time passing that moves the sky, and it happens on a shard
 * rather than on the proxy: a bed is a block, and the people in it are on whichever shard owns it.
 * Once the sky is shared a shard cannot simply skip its own night - the next answer from the proxy
 * would put it straight back into the dark - so it asks, and everybody's morning arrives together.</p>
 *
 * <p>Whether enough people slept is decided by the shard, by whatever rules it already has, because
 * that is where the beds and the sleepers are. The proxy is told the outcome, not asked to judge
 * it.</p>
 */
public record NightSkipRequest(UUID requestId, String serverName, long createdAtEpochMillis) {

    public NightSkipRequest {
        Objects.requireNonNull(requestId, "requestId");
        serverName = Messages.requireNonBlank(serverName, "serverName");
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static NightSkipRequest create(final String serverName) {
        return new NightSkipRequest(UUID.randomUUID(), serverName, System.currentTimeMillis());
    }
}
