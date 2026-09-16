package net.civmc.shards.velocity.playerdata;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Who the proxy has just handed to another shard, so that shard can tell an arrival from a login.
 *
 * <p>The two look identical from the destination's side: both claim a lock and restore a stored
 * snapshot. Only the proxy knows the difference, because only the proxy started the handover - so it
 * is recorded here as the transfer begins and read once, by the claim that follows it.</p>
 *
 * <p>Deliberately in memory and deliberately not authoritative. Nothing about ownership or a player's
 * data depends on it: the worst a lost entry causes is an arrival that is not announced as one. A
 * proxy restart in the middle of a crossing therefore costs a greeting, not correctness.</p>
 */
public final class InFlightTransfers {

    // Long enough to cover a crossing several times over, short enough that a player who never
    // arrived is not greeted as an arrival when they log in again much later
    private static final Duration LIFETIME = Duration.ofMinutes(1L);

    private final Map<UUID, Long> startedAtNanos = new ConcurrentHashMap<>();

    public void started(final UUID playerUuid) {
        // Opportunistic, so an entry for someone who never arrives cannot accumulate: the only other
        // thing that would remove it is a claim that never comes
        forgetExpired();
        this.startedAtNanos.put(playerUuid, System.nanoTime());
    }

    /**
     * Whether this player is arriving from another shard, consuming the record either way.
     *
     * <p>Read once: a second login is a login, however soon it follows.</p>
     */
    public boolean consumeIsArriving(final UUID playerUuid) {
        final Long startedAt = this.startedAtNanos.remove(playerUuid);
        return startedAt != null && System.nanoTime() - startedAt <= LIFETIME.toNanos();
    }

    private void forgetExpired() {
        final long now = System.nanoTime();
        this.startedAtNanos.entrySet()
            .removeIf(entry -> now - entry.getValue() > LIFETIME.toNanos());
    }
}
