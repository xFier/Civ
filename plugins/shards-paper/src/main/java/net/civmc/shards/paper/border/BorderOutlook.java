package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.BorderProbeRequest;
import net.civmc.shards.api.BorderProbeResponse;
import net.civmc.shards.api.BorderProbeResult;
import net.civmc.shards.api.BorderProbeStatus;
import net.civmc.shards.api.region.ShardPoint;
import net.civmc.shards.paper.rabbitmq.ShardsClient;

/**
 * What this shard believes lies beyond each of its edges.
 *
 * <p>Only the proxy knows, because only the proxy holds the shard map, so the answer has to be asked
 * for. That makes the timing the whole design here: this is consulted while drawing the border and
 * while deciding what to tell a player, both on the main thread, where waiting on a broker round trip
 * would be a stall on every player on the server.</p>
 *
 * <p>So it never waits. {@link #beyond} answers from what is already known and returns empty until an
 * answer has arrived, which is a fraction of a second after {@link #refresh} first asked. Being
 * briefly out of date is harmless because nothing acts on it - it decides what a player is
 * <em>shown</em>, while whether they may actually cross is decided by the proxy at the moment they
 * try.</p>
 */
public final class BorderOutlook {

    // Long enough that standing at a border is a handful of probes rather than one every pass, short
    // enough that a neighbour coming back is noticed while the player is still stood there
    private static final long FRESH_FOR_NANOS = TimeUnit.SECONDS.toNanos(5L);
    // Keyed per block, and players walk, so this would otherwise grow for as long as the server runs
    private static final int MAX_ENTRIES = 4096;

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
     * What lies past this face, if it is already known.
     *
     * <p>Never blocks, and never throws. Empty means "not yet" rather than "nothing there", which is
     * why a caller draws nothing rather than guessing: telling somebody the world ends when in truth
     * the neighbour had not answered yet is worse than telling them nothing for half a second.</p>
     */
    public Optional<Beyond> beyond(final EdgeSighting face) {
        return beyond(face.outsideX(), face.outsideZ());
    }

    /**
     * What is known about one block, which is only ever asked of blocks outside this shard.
     */
    public Optional<Beyond> beyond(final int blockX, final int blockZ) {
        final Known entry = this.known.get(key(blockX, blockZ));
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.beyond());
    }

    /**
     * Asks about every one of these faces whose answer is missing or stale, in one request.
     *
     * <p>One round trip for a whole stretch of border rather than one per face. A border is not a
     * single place - a doorway can sit beside a wall - so the faces in sight genuinely can have
     * different answers, and asking for them separately would be a message per block per player.</p>
     */
    public void refresh(final Collection<EdgeSighting> faces) {
        final long now = System.nanoTime();
        final List<ShardPoint> asking = new ArrayList<>();
        for (final EdgeSighting face : faces) {
            final long key = key(face.outsideX(), face.outsideZ());
            final Known entry = this.known.get(key);
            if (entry != null && now - entry.askedAtNanos() < FRESH_FOR_NANOS) {
                continue;
            }
            // Marked as asked before the request goes out, keeping any previous answer in place.
            // Otherwise every pass taken while one probe is in flight would start another
            this.known.put(key, new Known(entry == null ? null : entry.beyond(), now));
            asking.add(new ShardPoint(face.outsideX(), face.outsideZ()));
            if (asking.size() == BorderProbeRequest.MAX_BLOCKS) {
                break;
            }
        }
        if (asking.isEmpty()) {
            return;
        }
        if (this.known.size() > MAX_ENTRIES) {
            // Cleared rather than evicted one by one: these are cheap to ask for again, and the
            // alternative is keeping access times for entries that are only read while somebody
            // happens to be standing next to them
            this.known.clear();
        }
        this.client.probeBorder(BorderProbeRequest.create(this.serverName, asking))
            .whenComplete(this::record);
    }

    private void record(final BorderProbeResponse response, final Throwable error) {
        if (error != null) {
            // Left as whatever was known before. A probe that could not be sent says nothing about the
            // ground, and showing a wall because the broker hiccuped would be worse than showing
            // nothing
            this.logger.log(Level.FINE, "Could not probe a border", error);
            return;
        }
        if (response.failed()) {
            this.logger.warning("Border probe refused: " + response.failureMessage());
            return;
        }
        final long now = System.nanoTime();
        for (final BorderProbeResult result : response.results()) {
            this.known.put(key(result.x(), result.z()),
                new Known(new Beyond(result.status(), result.shardName()), now));
        }
    }

    /**
     * Two ints in one long, so the map is keyed without allocating a point per lookup - and this is
     * looked up on the main thread once per face in sight, every pass.
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

        /**
         * Whether crossing here is refused by the shard map itself rather than by anything that might
         * come back. Safe to act on without asking the proxy, because only a config change and a
         * restart can alter it.
         */
        public boolean permanentlyClosed() {
            return this.status == BorderProbeStatus.UNOWNED;
        }
    }

    private record Known(Beyond beyond, long askedAtNanos) {
    }
}
