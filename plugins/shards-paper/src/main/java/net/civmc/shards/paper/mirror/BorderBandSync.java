package net.civmc.shards.paper.mirror;

import io.papermc.paper.math.Position;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.BorderProbeRequest;
import net.civmc.shards.api.BorderProbeResponse;
import net.civmc.shards.api.BorderProbeResult;
import net.civmc.shards.api.ChunkStateRequest;
import net.civmc.shards.api.ChunkStateResponse;
import net.civmc.shards.api.mirror.ChunkSectionState;
import net.civmc.shards.api.mirror.ChunkState;
import net.civmc.shards.api.mirror.ChunkStateCodec;
import net.civmc.shards.api.region.ShardPoint;
import net.civmc.shards.api.region.ShardRegion;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Brings this server's own copy of the ground just past its borders up to date, once, at startup.
 *
 * <p>Every shard begins as a copy of the same world, and from then on each one only ever changes its
 * own ground. So a shard's copy of its neighbour's land is that land as it was at the split: the
 * right terrain, with nothing built, dug or levelled since. The mirror draws over that for the client
 * and deliberately writes none of it, which is right for a picture and leaves one thing unanswered -
 * the server is still simulating against the old blocks. A player walks into walls only this server
 * believes in, and a minecart carries its rider into them and suffocates them, with a clear view of
 * open ground the whole way.
 *
 * <p>This is not the mirror becoming real. The neighbour's blocks have been in this world since the
 * day it was copied; what is out of date is <em>which</em> day. Writing them again moves that day
 * forward, which is exactly what a shard split this morning would have had for nothing - the seed
 * being refreshed rather than a new kind of thing appearing. The mirror's own rule is untouched:
 * what is drawn for a client is still never written, and this runs before any of it.
 *
 * <p><strong>A thin strip, not the whole view.</strong> What matters is where a player can reach,
 * which is the first block or two past a seam. The rest of what they can see is the mirror's job and
 * costs nothing to leave alone.
 *
 * <p><strong>Terrain only.</strong> A chunk read carries block states, so nothing with contents is
 * written by this - no chest's items, no sign's words. Those stay drawn and unreal, which is where
 * the duplication risk lives and where it should stay.
 *
 * <p>Opportunistic from end to end. Neighbours restart when this server does, so one that does not
 * answer is skipped rather than waited for: this is a head start on the ground, and a shard that
 * missed its turn keeps a stale strip until the next restart, which is what every shard had before
 * this existed.
 */
public final class BorderBandSync {

    // How many chunk reads are in flight at once. Serving one costs a neighbour about 22ms of real
    // work and it is starting up too, so this is deliberately gentle rather than as fast as possible
    private static final int AT_A_TIME = 4;
    // Chunks written per tick. The point is that this runs while the server is otherwise idle, and a
    // whole band applied in one go is a stall somebody would see if anybody were on
    private static final int CHUNKS_PER_TICK = 2;
    // How long before trying again for ground nobody answered about. A neighbour that was down when
    // this server started is the whole reason: without this, its strip stays as this server's own
    // copy until the next restart, which on a network that restarts daily is a day of walking into
    // walls that are not there
    private static final long RETRY_TICKS = 20L * 60L;

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final ShardsClient client;
    private final MirrorView mirror;
    private final String serverName;
    private final Logger logger;
    private final int depthChunks;

    // Who owns each chunk column, asked for directly rather than through the cache the border view
    // keeps. That one is built for the handful of faces beside one player: it holds 4096 entries and
    // empties itself wholesale on the next past that, treats an answer as stale after five seconds,
    // and takes 128 blocks a request. A band is thousands of columns asked once, which is every one
    // of those working against it - the first run of this gave up on 3528 of 4599 for that reason
    private final Map<Long, String> owners = new HashMap<>();
    private List<Long> columns = List.of();
    private List<String> worlds = List.of();
    private final Deque<ChunkKey> waiting = new ArrayDeque<>();
    private final Deque<Runnable> writing = new ArrayDeque<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger read = new AtomicInteger();
    private final AtomicInteger written = new AtomicInteger();
    private final AtomicInteger unanswered = new AtomicInteger();
    // Ground nobody owns, which is not a failure and is never retried
    private final AtomicInteger nobodysGround = new AtomicInteger();
    // What is worth trying again for: a column whose owner would not say, and a chunk whose owner
    // would not send it
    private final Set<Long> askAgain = new HashSet<>();
    private final List<ChunkKey> readAgain = new ArrayList<>();
    private long startedAt;
    private boolean running;
    private boolean draining;

    public BorderBandSync(final JavaPlugin plugin, final ShardBorder border, final ShardsClient client,
                          final MirrorView mirror, final String serverName, final Logger logger,
                          final int depthChunks) {
        this.plugin = plugin;
        this.border = border;
        this.client = client;
        this.mirror = mirror;
        this.serverName = serverName;
        this.logger = logger;
        this.depthChunks = depthChunks;
    }

    /**
     * Works out the band and starts reading it. Called once, after the shard map has arrived.
     */
    public void start() {
        if (this.depthChunks <= 0 || !this.border.isConfigured()) {
            return;
        }
        this.startedAt = System.nanoTime();
        this.running = true;
        // Off the main thread: this walks every chunk in the box around this shard, which for a large
        // one is tens of thousands of containment tests
        Bukkit.getScheduler().runTaskAsynchronously(this.plugin, () -> {
            final Band band = band();
            Bukkit.getScheduler().runTask(this.plugin, () -> begin(band));
        });
    }

    private void begin(final Band band) {
        if (band.columns().isEmpty() || band.worlds().isEmpty()) {
            this.running = false;
            return;
        }
        this.columns = band.columns();
        this.worlds = band.worlds();
        this.logger.info("Bringing " + (this.columns.size() * this.worlds.size()) + " chunk(s) of "
            + "border band up to date from their owners, " + this.depthChunks + " chunk(s) deep");
        askWhoOwns(0);
    }

    /**
     * Every chunk column this server does not own that lies within the band of one it does.
     *
     * <p>Walked in chunks rather than blocks, which shard edges are guaranteed to fall on, and over
     * the box around this shard rather than along its outline - an outline can be notched and
     * cornered, and the box is one pass with no cases in it.
     */
    private Band band() {
        final ShardRegion.BoundingBox box = boxAroundEverything();
        if (box == null) {
            return new Band(List.of(), List.of());
        }
        final int fromX = (box.minX() >> 4) - this.depthChunks;
        final int toX = (box.maxX() >> 4) + this.depthChunks;
        final int fromZ = (box.minZ() >> 4) - this.depthChunks;
        final int toZ = (box.maxZ() >> 4) + this.depthChunks;
        final List<Long> found = new ArrayList<>();
        for (int chunkX = fromX; chunkX <= toX; chunkX++) {
            for (int chunkZ = fromZ; chunkZ <= toZ; chunkZ++) {
                if (this.border.isChunkOutside(chunkX, chunkZ) && nearOurOwn(chunkX, chunkZ)) {
                    found.add(column(chunkX, chunkZ));
                }
            }
        }
        final List<String> named = new ArrayList<>();
        for (final World world : Bukkit.getWorlds()) {
            // Shards divide the x/z plane across every world at once, so the same strip of the nether
            // and the end is somebody else's too, and suffocating in one is no better
            named.add(world.getName());
        }
        return new Band(found, named);
    }

    private boolean nearOurOwn(final int chunkX, final int chunkZ) {
        for (int x = -this.depthChunks; x <= this.depthChunks; x++) {
            for (int z = -this.depthChunks; z <= this.depthChunks; z++) {
                if (!this.border.isChunkOutside(chunkX + x, chunkZ + z)) {
                    return true;
                }
            }
        }
        return false;
    }

    private ShardRegion.BoundingBox boxAroundEverything() {
        ShardRegion.BoundingBox box = null;
        for (final ShardRegion region : this.border.regions()) {
            final ShardRegion.BoundingBox mine = region.boundingBox();
            box = box == null ? mine : new ShardRegion.BoundingBox(
                Math.min(box.minX(), mine.minX()), Math.min(box.minZ(), mine.minZ()),
                Math.max(box.maxX(), mine.maxX()), Math.max(box.maxZ(), mine.maxZ()));
        }
        return box;
    }

    /**
     * Asks the proxy who owns each column of the band, a request at a time.
     *
     * <p>Once per column and not once per chunk: shards divide the x/z plane across every world at
     * once, so the same strip of the nether belongs to whoever owns it in the overworld, and asking
     * again for each world is the same question three times.
     *
     * <p>One request in flight at a time, each carrying as many blocks as a probe allows. A band is
     * thousands of columns and this is a few dozen requests, made while nobody is online.
     */
    private void askWhoOwns(final int from) {
        if (from >= this.columns.size()) {
            queueTheChunks();
            return;
        }
        final int to = Math.min(from + BorderProbeRequest.MAX_BLOCKS, this.columns.size());
        final List<ShardPoint> asking = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            asking.add(new ShardPoint(chunkX(this.columns.get(index)) << 4,
                chunkZ(this.columns.get(index)) << 4));
        }
        this.client.probeBorder(BorderProbeRequest.create(this.serverName, asking))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> acceptOwners(from, to, response, error)));
    }

    private void acceptOwners(final int from, final int to, final BorderProbeResponse response,
                              final Throwable error) {
        if (error != null || response == null || response.failed()) {
            // Nobody said who owns these. Worth asking again, because the answer comes from the proxy
            // and a proxy that cannot answer now is one that can answer in a minute
            for (int index = from; index < to; index++) {
                this.askAgain.add(this.columns.get(index));
            }
        } else {
            for (final BorderProbeResult result : response.results()) {
                this.owners.put(column(result.x() >> 4, result.z() >> 4), result.shardName());
            }
        }
        askWhoOwns(to);
    }

    /**
     * Turns the columns whose owner is known into one chunk to read per world.
     */
    private void queueTheChunks() {
        for (final Long band : this.columns) {
            if (this.askAgain.contains(band)) {
                continue;
            }
            final String owner = this.owners.get(band);
            if (owner == null || owner.isBlank()) {
                // Ground nobody owns. Not a failure and never retried: this server's copy is already
                // the best there is. Counted on its own, or the summary reads as though these had been
                // lost - the band runs outward from this shard's own box, so the whole outer rim of
                // the map lands here and it is by far the biggest number in it
                this.nobodysGround.addAndGet(this.worlds.size());
                continue;
            }
            for (final String world : this.worlds) {
                this.waiting.add(new ChunkKey(world, chunkX(band), chunkZ(band)));
            }
        }
        if (this.waiting.isEmpty()) {
            finishIfDone();
            return;
        }
        if (!this.draining) {
            // One timer for the life of the server rather than one per pass: a retry adds to the
            // same queue and is drained by the same task
            this.draining = true;
            Bukkit.getScheduler().runTaskTimer(this.plugin, this::drainWrites, 1L, 1L);
        }
        fillTheQueue();
    }

    /**
     * Starts reads for as many chunks as may be in flight at once.
     */
    private void fillTheQueue() {
        while (!this.waiting.isEmpty() && this.inFlight.get() < AT_A_TIME) {
            final ChunkKey key = this.waiting.poll();
            this.inFlight.incrementAndGet();
            read(key, this.owners.get(column(key.x(), key.z())));
        }
    }

    private void read(final ChunkKey key, final String owner) {
        this.client.chunkState(owner,
                ChunkStateRequest.create(this.serverName, key.world(), key.x(), key.z()))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> accept(key, response, error)));
    }

    private void accept(final ChunkKey key, final ChunkStateResponse response, final Throwable error) {
        this.inFlight.decrementAndGet();
        fillTheQueue();
        if (error != null || response == null || response.failed()) {
            // A neighbour starting up, or down. Our own copy stays, which is what it already was,
            // and it is asked for again: the usual reason is that it had not finished booting
            this.unanswered.incrementAndGet();
            this.readAgain.add(key);
            finishIfDone();
            return;
        }
        final World world = Bukkit.getWorld(key.world());
        if (world == null) {
            finishIfDone();
            return;
        }
        final ChunkState theirs;
        try {
            theirs = ChunkStateCodec.fromBytes(Base64.getDecoder().decode(response.state()));
        } catch (final RuntimeException unreadable) {
            this.logger.log(Level.WARNING, "Could not read " + key + " as its owner sent it", unreadable);
            finishIfDone();
            return;
        }
        this.read.incrementAndGet();
        world.getChunkAtAsync(key.x(), key.z())
            .thenApply(Chunk::getChunkSnapshot)
            .thenApplyAsync(ours ->
                differences(key, theirs, ours, world.getMinHeight(), world.getMaxHeight()))
            .whenComplete((changed, failure) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> queueWrite(key, world, changed, failure)));
    }

    private void queueWrite(final ChunkKey key, final World world, final Map<Position, BlockData> changed,
                            final Throwable failure) {
        if (failure != null) {
            this.logger.log(Level.WARNING, "Could not compare " + key + " with our own copy", failure);
            finishIfDone();
            return;
        }
        if (changed == null || changed.isEmpty()) {
            finishIfDone();
            return;
        }
        this.writing.add(() -> apply(key, world, changed));
    }

    /**
     * Writes one chunk's difference into this server's own world.
     *
     * <p>Without physics, and that is not an optimisation. Applying a neighbour's ground with physics
     * on would have sand fall, water flow and redstone fire on ground this server is not authoritative
     * for - the exact thing the border seal exists to prevent, caused by the very sync meant to stop
     * the two servers disagreeing.
     */
    private void apply(final ChunkKey key, final World world, final Map<Position, BlockData> changed) {
        for (final Map.Entry<Position, BlockData> block : changed.entrySet()) {
            world.getBlockAt(block.getKey().blockX(), block.getKey().blockY(), block.getKey().blockZ())
                .setBlockData(block.getValue(), false);
        }
        this.written.addAndGet(changed.size());
        // What the mirror worked out about this chunk was worked out against a copy that has just
        // moved underneath it, so it is not a difference from anything any more
        this.mirror.forgetChunk(key);
    }

    /**
     * Applies queued chunks a couple at a time, so nothing is a visible stall.
     */
    private void drainWrites() {
        for (int applied = 0; applied < CHUNKS_PER_TICK && !this.writing.isEmpty(); applied++) {
            this.writing.poll().run();
        }
        finishIfDone();
    }

    private void finishIfDone() {
        if (!this.running || !this.waiting.isEmpty() || !this.writing.isEmpty()
            || this.inFlight.get() > 0) {
            return;
        }
        this.running = false;
        this.logger.info(String.format(
            "Border band brought up to date in %dms: %d chunk(s) read, %d block(s) written, %d chunk(s) "
                + "owned by nobody and left alone, %d chunk(s) left as this server's own copy because "
                + "nobody answered for them",
            (System.nanoTime() - this.startedAt) / 1_000_000L, this.read.get(), this.written.get(),
            this.nobodysGround.get(), this.unanswered.get()));
        tryTheRestLater();
    }

    /**
     * Comes back for whatever nobody would answer about, until somebody does.
     *
     * <p>A neighbour is nearly always down for the same reason this server is up: the network
     * restarts together, and whoever boots first asks whoever boots second and is told nothing. Left
     * there, that shard keeps a strip of ground as it was on the day of the split until the next
     * restart - so on a network that restarts daily, a neighbour a minute late costs a day of players
     * walking into walls that are not there.
     *
     * <p>Only what is worth asking again. Ground owned by nobody is not retried: there is no copy of
     * it anywhere better than our own, and asking would never stop.
     */
    private void tryTheRestLater() {
        if (this.askAgain.isEmpty() && this.readAgain.isEmpty()) {
            return;
        }
        final int columnsLeft = this.askAgain.size();
        final int chunksLeft = this.readAgain.size();
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            this.logger.info("Asking again for " + columnsLeft + " column(s) and " + chunksLeft
                + " chunk(s) of border band whose owner did not answer");
            this.running = true;
            this.startedAt = System.nanoTime();
            this.read.set(0);
            this.written.set(0);
            this.unanswered.set(0);
            this.nobodysGround.set(0);
            this.waiting.addAll(this.readAgain);
            this.readAgain.clear();
            this.columns = new ArrayList<>(this.askAgain);
            this.askAgain.clear();
            if (this.columns.isEmpty()) {
                queueTheChunks();
            } else {
                askWhoOwns(0);
            }
        }, RETRY_TICKS);
    }

    /**
     * Where the owner's chunk and ours disagree. Off the main thread: it reads a snapshot, not the
     * world.
     */
    private Map<Position, BlockData> differences(final ChunkKey key, final ChunkState theirs,
                                                 final ChunkSnapshot ours, final int minHeight,
                                                 final int maxHeight) {
        final Map<Position, BlockData> changed = new HashMap<>();
        final int sectionCount = Math.min(theirs.sections().size(), (maxHeight - minHeight) / 16);
        for (int section = 0; section < sectionCount; section++) {
            final ChunkSectionState theirSection = theirs.sections().get(section);
            final int baseY = theirs.minY() + (section << 4);
            // Only when the two worlds are the same depth, for the reason the mirror gives: section
            // indices count from the bottom of the world, so on different depths the same index is a
            // different height and this shortcut would skip a slice that really does differ
            if (theirSection.isEmpty() && theirs.minY() == minHeight && ours.isSectionEmpty(section)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        final String theirBlock = theirSection.isEmpty()
                            ? "minecraft:air"
                            : theirSection.palette()
                                .get(theirSection.indices()[ChunkSectionState.indexOf(x, y, z)]);
                        if (theirBlock.equals(ours.getBlockData(x, baseY + y, z).getAsString())) {
                            continue;
                        }
                        changed.put(Position.block((key.x() << 4) + x, baseY + y, (key.z() << 4) + z),
                            Bukkit.createBlockData(theirBlock));
                    }
                }
            }
        }
        return changed;
    }

    private static long column(final int chunkX, final int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int chunkX(final long packed) {
        return (int) (packed >> 32);
    }

    private static int chunkZ(final long packed) {
        return (int) packed;
    }

    /**
     * The band as it is worked out: the columns to ask about, and the worlds each one exists in.
     */
    private record Band(List<Long> columns, List<String> worlds) {
    }
}
