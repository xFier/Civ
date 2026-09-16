package net.civmc.shards.paper.border;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.BorderProbeRequest;
import net.civmc.shards.api.BorderProbeResponse;
import net.civmc.shards.api.BorderProbeStatus;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Location;

/**
 * What this shard believes lies beyond each of its edges.
 *
 * <p>Only the proxy knows, because only the proxy holds the shard map, so the answer has to be asked
 * for. That makes the timing the whole design here: this is consulted from the move handler on the
 * main thread, where waiting on a broker round trip for every step near a border would be a stall on
 * every player on the server.</p>
 *
 * <p>So it never waits. {@link #beyond} answers from what is already known and returns empty the very
 * first time, starting a refresh in the background; the caller shows nothing until an answer arrives,
 * a fraction of a second later. Being briefly out of date is harmless because nothing acts on it - it
 * decides what a player is <em>shown</em>, while whether they may actually cross is decided by the
 * proxy at the moment they try.</p>
 */
public final class BorderOutlook {

    // Long enough that walking along a border is a handful of probes rather than one per block, short
    // enough that a neighbour coming back is noticed while the player is still stood there
    private static final long FRESH_FOR_NANOS = TimeUnit.SECONDS.toNanos(5L);
    // Keyed per block, and players walk, so this would otherwise grow for as long as the server runs
    private static final int MAX_ENTRIES = 512;

    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    private final Map<Long, Known> known = new ConcurrentHashMap<>();

    public BorderOutlook(final ShardsClient client, final String serverName, final Logger logger) {
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
    }

    /**
     * What lies past this edge, if it is already known.
     *
     * <p>Never blocks, and never throws. Empty means "not yet" rather than "nothing there".</p>
     */
    public Optional<Beyond> beyond(final Location near, final EdgeSighting edge) {
        final long key = key(edge.outsideX(), edge.outsideZ());
        final Known entry = this.known.get(key);
        if (entry != null && System.nanoTime() - entry.askedAtNanos() < FRESH_FOR_NANOS) {
            return Optional.ofNullable(entry.beyond());
        }
        refresh(key, near, edge, entry);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.beyond());
    }

    private void refresh(final long key, final Location near, final EdgeSighting edge, final Known stale) {
        // Marked as asked before the request goes out, keeping the previous answer in place. Otherwise
        // every step taken while one probe is in flight would start another
        final Known pending = new Known(stale == null ? null : stale.beyond(), System.nanoTime());
        if (this.known.put(key, pending) == null && this.known.size() > MAX_ENTRIES) {
            // Cleared rather than evicted one by one: these are cheap to ask for again, and the
            // alternative is keeping access times for entries that are only read while somebody
            // happens to be standing next to them
            this.known.clear();
            this.known.put(key, pending);
        }
        final PlayerLocation at = new PlayerLocation(near.getWorld().getName(),
            edge.outsideX() + 0.5, near.getY(), edge.outsideZ() + 0.5);
        this.client.probeBorder(BorderProbeRequest.create(this.serverName, at))
            .whenComplete((response, error) -> record(key, response, error));
    }

    private void record(final long key, final BorderProbeResponse response, final Throwable error) {
        if (error != null) {
            // Left as whatever was known before. A probe that could not be sent says nothing about the
            // ground, and showing a wall because the broker hiccuped would be worse than showing
            // nothing
            this.logger.log(Level.FINE, "Could not probe a border", error);
            return;
        }
        if (response.status() == BorderProbeStatus.ERROR) {
            this.logger.warning("Border probe refused: " + response.failureMessage());
            return;
        }
        this.known.put(key, new Known(new Beyond(response.status(), response.shardName()), System.nanoTime()));
    }

    /**
     * Two ints in one long, so the map is keyed without allocating a point per lookup - and this is
     * looked up on the main thread every time a player near a border changes block.
     */
    private static long key(final int blockX, final int blockZ) {
        return ((long) blockX << 32) ^ (blockZ & 0xFFFFFFFFL);
    }

    /**
     * @param shardName who owns it, null when nobody does
     */
    public record Beyond(BorderProbeStatus status, String shardName) {

        public boolean crossable() {
            return this.status == BorderProbeStatus.CROSSABLE;
        }
    }

    private record Known(Beyond beyond, long askedAtNanos) {
    }
}
