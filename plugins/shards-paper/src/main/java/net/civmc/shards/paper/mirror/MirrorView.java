package net.civmc.shards.paper.mirror;

import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.ChunkStateRequest;
import net.civmc.shards.api.ChunkStateResponse;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.mirror.BlockUpdate;
import net.civmc.shards.api.mirror.ChunkSectionState;
import net.civmc.shards.api.mirror.ChunkState;
import net.civmc.shards.api.mirror.ChunkStateCodec;
import net.civmc.shards.api.mirror.MirroredEntity;
import net.civmc.shards.api.region.ShardPoint;
import net.civmc.shards.paper.border.BorderOutlook;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Shows a player what the neighbouring shard really has on the ground past the border.
 *
 * <p>Without this, the far side of a border is <em>virgin terrain</em>. Every shard generates the same
 * world from the same seed, so unmodified ground matches exactly - but everything built on the
 * neighbour is absent from this server's copy, and the gap grows for as long as the map lives. A year
 * in, standing at a border means looking at empty hills where there is a city.</p>
 *
 * <p>The neighbour sends its chunk as it really is, and the difference against this server's own copy
 * is worked out here and sent to clients as block changes. <strong>Nothing is written.</strong> The
 * mirrored blocks never enter this server's world, so no plugin can find them, nothing can be
 * reinforced, counted or broken, and the border stays as impermeable as it was - the picture has no
 * back door, which is the whole reason it is drawn this way.</p>
 *
 * <p>Working out the difference here, rather than having the neighbour remember what it has changed,
 * is what makes drift harmless. The two copies diverge further every week; the difference found is
 * simply larger, never wrong, and the worst case - a chunk rebuilt from scratch - costs no more than
 * sending the chunk would have. It also means there is nothing to log, nothing to replay and no
 * catch-up at startup: a chunk asked for now is current by construction.</p>
 */
public final class MirrorView implements Listener {

    // A backstop, not the way changes are noticed - that is what the announcements are for. It used
    // to be sixty seconds and it was the whole cost of the mirror: seventy chunks re-read every thirty
    // seconds for a player standing still, to find nothing. Long now, because the only thing it has to
    // catch is an announcement that never arrived
    private static final long FRESH_FOR_NANOS = TimeUnit.MINUTES.toNanos(10L);
    // How long a chunk nobody has been near is kept. Generous on purpose: this is not an optimisation,
    // it is the only thing stopping one entry per chunk of border the server has ever had somebody
    // stand at, held for as long as it runs and written to disk every five minutes along with it
    private static final long KEEP_UNSEEN_FOR_NANOS = TimeUnit.HOURS.toNanos(1L);

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final BorderOutlook outlook;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    private final int radiusChunks;
    private final MirrorMetrics metrics;
    private final MirrorEntities entities;

    // One copy per chunk, shared by everybody on this server. A hundred players at one seam ask for
    // the same ground, and it is the same answer for all of them
    private final Map<ChunkKey, Mirrored> mirrored = new ConcurrentHashMap<>();
    private final Set<ChunkKey> inFlight = ConcurrentHashMap.newKeySet();
    // Chunks a neighbour announced a change to while we were part-way through reading them. The read
    // already in flight was taken before that change, so installing its answer would throw the
    // correction away and leave the block missing until the backstop. Marked here, and read again
    private final Set<ChunkKey> changedWhileFetching = ConcurrentHashMap.newKeySet();
    // Which chunks each player has been sent, so a diff is sent once rather than every pass. Dropped
    // when a chunk leaves their range, because their client discards it and will be sent this
    // server's own version again if they come back
    private final Map<UUID, Set<ChunkKey>> shown = new ConcurrentHashMap<>();
    // When somebody was last near each chunk, which is not the same as when it was last read: a border
    // nobody visits is re-read every ten minutes for as long as anyone is in range and then never again
    private final Map<ChunkKey, Long> lastNearby = new ConcurrentHashMap<>();

    public MirrorView(final JavaPlugin plugin, final ShardBorder border, final BorderOutlook outlook,
                      final ShardsClient client, final String serverName, final Logger logger,
                      final int radiusChunks, final MirrorMetrics metrics,
                      final MirrorEntities entities) {
        this.plugin = plugin;
        this.border = border;
        this.outlook = outlook;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
        this.radiusChunks = radiusChunks;
        this.metrics = metrics;
        this.entities = entities;
    }

    /**
     * Brings one player's view of the far side up to date. Main thread.
     */
    public void update(final Player viewer) {
        if (!this.border.isConfigured()) {
            return;
        }
        // Costs a handful of comparisons wherever they stand, and says no for everybody who is not
        // near a border - without it this walks every chunk around every player on the server
        if (!this.border.outlineWithin(viewer.getLocation().getBlockX(), viewer.getLocation().getBlockZ(),
            this.radiusChunks << 4)) {
            forgetOutOfRange(viewer, Set.of());
            return;
        }
        final World world = viewer.getWorld();
        final Chunk standingOn = viewer.getLocation().getChunk();
        final Set<ChunkKey> inRange = new HashSet<>();
        final List<ShardPoint> askingWhoOwns = new ArrayList<>();

        for (int x = -this.radiusChunks; x <= this.radiusChunks; x++) {
            for (int z = -this.radiusChunks; z <= this.radiusChunks; z++) {
                final int chunkX = standingOn.getX() + x;
                final int chunkZ = standingOn.getZ() + z;
                if (!this.border.isChunkOutside(chunkX, chunkZ)) {
                    continue;
                }
                // Any block in it identifies the owner, because the whole chunk has one
                final ShardPoint foreignBlock = new ShardPoint(chunkX << 4, chunkZ << 4);
                final ChunkKey key = new ChunkKey(world.getName(), chunkX, chunkZ);
                inRange.add(key);
                this.lastNearby.put(key, System.nanoTime());
                show(viewer, key, foreignBlock, askingWhoOwns);
            }
        }
        // One round trip for every chunk whose owner is not known yet, rather than one each
        this.outlook.refreshBlocks(askingWhoOwns);
        forgetOutOfRange(viewer, inRange);
    }

    private void show(final Player viewer, final ChunkKey key, final ShardPoint foreignBlock,
                      final List<ShardPoint> askingWhoOwns) {
        final Mirrored current = this.mirrored.get(key);
        if (current == null || current.stale()
            || System.nanoTime() - current.fetchedAtNanos() > FRESH_FOR_NANOS) {
            fetch(key, foreignBlock, askingWhoOwns);
        }
        if (current == null) {
            return;
        }
        final Set<ChunkKey> alreadyShown = this.shown.computeIfAbsent(viewer.getUniqueId(),
            ignored -> ConcurrentHashMap.newKeySet());
        if (!alreadyShown.add(key)) {
            return;
        }
        if (!current.send().isEmpty()) {
            viewer.sendMultiBlockChange(current.send());
        }
        // The contents of a build rather than its walls. After the blocks, so a frame is never hung in
        // front of a wall that has not arrived yet
        this.entities.show(viewer, key.world(), key.x(), key.z(), current.entities());
    }

    /**
     * Asks the shard that owns a chunk what is in it, once.
     */
    private void fetch(final ChunkKey key, final ShardPoint foreignBlock, final List<ShardPoint> askingWhoOwns) {
        final Optional<BorderOutlook.Beyond> owner = this.outlook.beyond(foreignBlock.x(), foreignBlock.z());
        if (owner.isEmpty()) {
            // Not known yet. Asked for below, and picked up on a later pass - the same "draw nothing
            // rather than guess" rule the border itself follows
            askingWhoOwns.add(foreignBlock);
            return;
        }
        if (owner.get().shardName() == null || owner.get().shardName().isBlank()) {
            // Ground nobody owns. There is no neighbour to ask and nothing to show but our own copy
            return;
        }
        if (!this.inFlight.add(key)) {
            return;
        }
        // Anything announced from here on was changed after this read was asked for, so it may not be
        // in the answer. Cleared here and never earlier: an announcement that arrived before the
        // request is already in the neighbour's snapshot, because the snapshot is taken afterwards
        this.changedWhileFetching.remove(key);
        this.client.chunkState(owner.get().shardName(),
                ChunkStateRequest.create(this.serverName, key.world(), key.x(), key.z()))
            .whenComplete((response, error) -> accept(key, response, error));
    }

    private void accept(final ChunkKey key, final ChunkStateResponse response, final Throwable error) {
        if (error != null || response == null || response.failed()) {
            // Left showing our own copy. A neighbour that cannot answer is not a reason to draw a
            // hole in the world, and the fetch is retried on the next pass
            this.logger.log(Level.FINE, "Could not read " + key + " from its owner", error);
            this.inFlight.remove(key);
            return;
        }
        // Held until the chunk is installed, not merely until the answer arrives. The difference is
        // worked out off the main thread and then waits for a tick, and a chunk that stopped being in
        // flight before that is asked for again by the very next pass: a second whole read of a
        // neighbour's chunk, thrown away, which can also land an older answer on top of a newer one
        try {
            final ChunkState state = ChunkStateCodec.fromBytes(Base64.getDecoder().decode(response.state()));
            final String publisherId = response.publisherId();
            final long revision = response.revision();
            final List<MirroredEntity> entities = response.entities();
            diff(key, state).whenComplete((blocks, failure) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> installed(key, blocks, failure, publisherId, revision, entities)));
        } catch (final RuntimeException unreadable) {
            this.logger.log(Level.WARNING, "Could not read " + key + " as its owner sent it", unreadable);
            this.inFlight.remove(key);
        }
    }

    /**
     * The end of a fetch, whichever way it went, and the only place a chunk stops being in flight once
     * its answer has arrived. Main thread.
     */
    private void installed(final ChunkKey key, final Map<Position, BlockData> blocks,
                           final Throwable failure, final String publisherId, final long revision,
                           final List<MirroredEntity> entities) {
        try {
            if (failure != null) {
                this.logger.log(Level.FINE, "Could not compare " + key + " with our own copy", failure);
                return;
            }
            install(key, blocks, publisherId, revision, entities);
        } finally {
            this.inFlight.remove(key);
        }
    }

    /**
     * Puts a freshly read chunk in place, and works out what has to be said about it.
     *
     * <p>A block that was in the last picture and is not in this one has to be put back, or a
     * neighbour who demolishes something leaves it standing on everybody else's screen forever. It is
     * put back to whatever this server's own copy holds, which for a block nobody has touched is
     * simply the block itself and costs the client nothing.</p>
     *
     * <p>Main thread: it reads this server's own world, and it decides who has to be told again.</p>
     */
    private void install(final ChunkKey key, final Map<Position, BlockData> blocks,
                         final String publisherId, final long revision,
                         final List<MirroredEntity> entities) {
        final Mirrored previous = this.mirrored.get(key);
        // Announced while this was being read, so of the two pictures this is the older one
        final boolean overtaken = this.changedWhileFetching.remove(key);
        if (overtaken && previous != null) {
            // Keep the corrected picture and read again. Installing this instead is the one order that
            // shows a block being taken back: the change appears, disappears, and returns a second
            // later when the next read lands
            this.mirrored.put(key, previous.readAgain());
            return;
        }
        final Map<Position, BlockData> send = new HashMap<>(blocks);
        final World world = Bukkit.getWorld(key.world());
        if (previous != null && world != null) {
            for (final Position position : previous.blocks().keySet()) {
                if (!blocks.containsKey(position)) {
                    send.put(position, world.getBlockAt(position.blockX(), position.blockY(),
                        position.blockZ()).getBlockData());
                }
            }
        }
        this.mirrored.put(key, new Mirrored(blocks, send, System.nanoTime(), overtaken, publisherId,
            revision, entities));
        if (previous != null && previous.blocks().equals(blocks)
            && previous.entities().equals(entities)) {
            // Nothing has changed over there, so nobody needs telling again
            return;
        }
        for (final Set<ChunkKey> alreadyShown : this.shown.values()) {
            alreadyShown.remove(key);
        }
    }

    /**
     * Works out what the neighbour has that we do not.
     *
     * <p>The snapshot is taken on the main thread because that is the only thing that has to be, and
     * the comparison - sixteen thousand blocks a section - is done off it. Only the blocks that
     * actually differ are turned into {@link BlockData}, which is why that last step is cheap enough
     * to come back to the main thread for.</p>
     */
    private CompletableFuture<Map<Position, BlockData>> diff(final ChunkKey key, final ChunkState theirs) {
        final World world = Bukkit.getWorld(key.world());
        if (world == null) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return world.getChunkAtAsync(key.x(), key.z())
            .thenApply(Chunk::getChunkSnapshot)
            .thenApplyAsync(ours -> timedDifferences(key, theirs, ours, world.getMinHeight(),
                world.getMaxHeight()))
            .thenApply(MirrorView::toBlockData);
    }

    private Map<Position, String> timedDifferences(final ChunkKey key, final ChunkState theirs,
                                                   final ChunkSnapshot ours, final int minHeight,
                                                   final int maxHeight) {
        final long startedAt = System.nanoTime();
        final Map<Position, String> changed = differences(key, theirs, ours, minHeight, maxHeight);
        this.metrics.diffed(System.nanoTime() - startedAt, changed.size());
        return changed;
    }

    private Map<Position, String> differences(final ChunkKey key, final ChunkState theirs,
                                              final ChunkSnapshot ours, final int minHeight,
                                              final int maxHeight) {
        final Map<Position, String> changed = new HashMap<>();
        final int sectionCount = Math.min(theirs.sections().size(), (maxHeight - minHeight) / 16);
        for (int section = 0; section < sectionCount; section++) {
            final ChunkSectionState theirSection = theirs.sections().get(section);
            final int baseY = theirs.minY() + (section << 4);
            // Only when the two worlds are the same depth. Section indices are counted from the
            // bottom of the world, so on worlds of different depths the same index is a different
            // height and this shortcut would skip a slice that really does differ
            if (theirSection.isEmpty() && theirs.minY() == minHeight && ours.isSectionEmpty(section)) {
                continue;
            }
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        compare(key, changed, theirSection, ours, baseY + y, x, y, z);
                    }
                }
            }
        }
        return changed;
    }

    private void compare(final ChunkKey key, final Map<Position, String> changed,
                         final ChunkSectionState theirSection, final ChunkSnapshot ours, final int worldY,
                         final int x, final int y, final int z) {
        final int worldX = (key.x() << 4) + x;
        final int worldZ = (key.z() << 4) + z;
        // No per-block ownership test. Shard edges fall on chunk boundaries, so a chunk this was
        // fetched for is foreign all the way through - and this runs sixteen thousand times a section,
        // where a polygon test per block was the single most expensive thing the mirror did
        final String theirBlock = theirSection.isEmpty()
            ? "minecraft:air"
            : theirSection.palette().get(theirSection.indices()[ChunkSectionState.indexOf(x, y, z)]);
        final String ourBlock = ours.getBlockData(x, worldY, z).getAsString();
        if (!theirBlock.equals(ourBlock)) {
            changed.put(Position.block(worldX, worldY, worldZ), theirBlock);
        }
    }

    private static Map<Position, BlockData> toBlockData(final Map<Position, String> changed) {
        final Map<Position, BlockData> blocks = new HashMap<>(changed.size());
        for (final Map.Entry<Position, String> entry : changed.entrySet()) {
            blocks.put(entry.getKey(), Bukkit.createBlockData(entry.getValue()));
        }
        return blocks;
    }

    /**
     * Drops what a player has walked away from.
     *
     * <p>Blocks need nothing said, because a client throws a chunk away when it leaves range and is
     * sent this server's own version of it if they come back. The drawn entities belong to no chunk,
     * so they would stand in an unloaded world forever unless they are taken away by name.</p>
     */
    private void forgetOutOfRange(final Player viewer, final Set<ChunkKey> inRange) {
        final Set<ChunkKey> alreadyShown = this.shown.get(viewer.getUniqueId());
        if (alreadyShown == null) {
            return;
        }
        final Iterator<ChunkKey> shownChunks = alreadyShown.iterator();
        while (shownChunks.hasNext()) {
            final ChunkKey key = shownChunks.next();
            if (!inRange.contains(key)) {
                shownChunks.remove();
                this.entities.forget(viewer, key.world(), key.x(), key.z());
            }
        }
    }

    /**
     * Applies what a neighbour says has just changed, to everybody who is looking at that chunk.
     *
     * <p>This is what the mirror is for: a block placed on the far side appears here at once, instead
     * of whenever the chunk next happened to be read. Ignored for a chunk nobody here has fetched -
     * the announcement goes to every shard, and most of them are not looking.</p>
     *
     * <p>The cached picture is corrected rather than thrown away. A block the neighbour now has that
     * matches our own copy stops being a difference at all and is dropped, which is what keeps the
     * picture from growing forever as the two worlds converge again.</p>
     *
     * <p>Main thread: it reads this server's own blocks and sends to players.</p>
     */
    public void applyUpdate(final ChunkUpdateMessage update) {
        final ChunkKey key = new ChunkKey(update.world(), update.chunkX(), update.chunkZ());
        if (this.inFlight.contains(key)) {
            // This chunk is being read right now, and the read was asked for before this change, so
            // the answer on its way may not have it. Recorded whether or not there is a picture to
            // correct below: a first fetch has nothing to correct and is the widest window of the lot,
            // the one that arriving on a shard opens for every chunk along the border at once
            this.changedWhileFetching.add(key);
        }
        final Mirrored current = this.mirrored.get(key);
        if (current == null) {
            return;
        }
        final boolean sameNumbering = update.publisherId().equals(current.publisherId());
        if (sameNumbering && update.revision() <= current.revision()) {
            // Already accounted for. The broker can hand the same message over twice, and applying an
            // old one again would put back a block that has since changed
            return;
        }
        // Either a number was skipped, or the owner has restarted and is numbering from the start
        // again. What has arrived is still the most recent thing we know, so it is applied - but
        // something between here and the last one is missing, and only a full read can say what
        final boolean missedOne = !sameNumbering || update.revision() != current.revision() + 1L;
        if (missedOne) {
            this.metrics.missedAnnouncement();
        }
        final World world = Bukkit.getWorld(update.world());
        if (world == null) {
            return;
        }
        final Map<Position, BlockData> blocks = new HashMap<>(current.blocks());
        final Map<Position, BlockData> send = new HashMap<>();
        for (final BlockUpdate block : update.updates()) {
            final Position position = Position.block(block.x(), block.y(), block.z());
            final BlockData theirs = Bukkit.createBlockData(block.blockData());
            final BlockData ours = world.getBlockAt(block.x(), block.y(), block.z()).getBlockData();
            if (theirs.equals(ours)) {
                // The two copies agree here again, so there is nothing to draw - but anyone who was
                // shown the old difference has to be told, which is what our own block does
                blocks.remove(position);
                send.put(position, ours);
            } else {
                blocks.put(position, theirs);
                send.put(position, theirs);
            }
        }
        // Keeps the fetch time, because nothing has been re-read - only corrected
        this.mirrored.put(key, new Mirrored(blocks, blocks, current.fetchedAtNanos(),
            current.stale() || missedOne, update.publisherId(), update.revision(), current.entities()));
        for (final Player viewer : Bukkit.getOnlinePlayers()) {
            final Set<ChunkKey> alreadyShown = this.shown.get(viewer.getUniqueId());
            if (alreadyShown != null && alreadyShown.contains(key)) {
                viewer.sendMultiBlockChange(send);
            }
        }
    }

    /**
     * Draws one chunk for one player again, because something rubbed their copy of it out.
     *
     * <p>Only forgets that they were shown it; the next pass sends it, which is under a second. Doing
     * it that way rather than sending here means one place decides what a player is shown, and a
     * repair cannot get ahead of a fetch that has not finished.</p>
     */
    public void redraw(final Player viewer, final int chunkX, final int chunkZ) {
        final Set<ChunkKey> alreadyShown = this.shown.get(viewer.getUniqueId());
        if (alreadyShown != null) {
            alreadyShown.remove(new ChunkKey(viewer.getWorld().getName(), chunkX, chunkZ));
        }
    }

    /**
     * Drops the chunks nobody has been near for an hour.
     *
     * <p>Without this there is one entry for every chunk of border anybody has ever stood at, kept for
     * as long as the server runs and written to disk with the rest every five minutes. A player who
     * walks a long border once leaves it there forever.</p>
     *
     * <p>It costs a chunk read when somebody comes back, which is the cheap half of the mirror and
     * happens under a second. It also makes the saved file self-limiting: what is restored and then
     * never visited is dropped within the hour and is not written again.</p>
     */
    public void forgetWhatNobodyIsLookingAt() {
        final long now = System.nanoTime();
        int dropped = 0;
        final Iterator<Map.Entry<ChunkKey, Mirrored>> chunks = this.mirrored.entrySet().iterator();
        while (chunks.hasNext()) {
            final ChunkKey key = chunks.next().getKey();
            final Long nearby = this.lastNearby.get(key);
            if (nearby != null && now - nearby <= KEEP_UNSEEN_FOR_NANOS) {
                continue;
            }
            // Not while it is being read: the fetch would install itself back into an entry that has
            // just been dropped, and the picture would be there with nothing recording that anybody
            // had asked for it
            if (this.inFlight.contains(key)) {
                continue;
            }
            chunks.remove();
            this.lastNearby.remove(key);
            dropped++;
        }
        if (dropped > 0) {
            this.logger.fine("Forgot " + dropped + " mirrored chunk(s) nobody has been near for an hour");
        }
    }

    /**
     * Puts back what was saved from the last run, before anybody is online.
     *
     * <p>Every chunk comes back marked to be read again, so what is restored is shown at once and then
     * corrected the moment somebody looks at it. That is what keeps this from being a catch-up log:
     * nothing loaded is trusted, it is only drawn while the truth is being fetched - and it is all
     * there is to draw when the owner cannot be reached at all.</p>
     *
     * <p>A block the game no longer knows is dropped one block at a time rather than losing the chunk,
     * because a saved mirror outlives a game update.</p>
     */
    public void restore(final List<MirrorStore.Saved> saved) {
        int blocksRestored = 0;
        int unknownBlocks = 0;
        for (final MirrorStore.Saved chunk : saved) {
            final Map<Position, BlockData> blocks = new HashMap<>(chunk.blocks().size());
            for (final MirrorStore.SavedBlock block : chunk.blocks()) {
                try {
                    blocks.put(Position.block(block.x(), block.y(), block.z()),
                        Bukkit.createBlockData(block.blockData()));
                } catch (final IllegalArgumentException noSuchBlock) {
                    unknownBlocks++;
                }
            }
            if (blocks.isEmpty()) {
                continue;
            }
            blocksRestored += blocks.size();
            // Treated as though somebody had just been there, so an hour of nobody going near it has
            // to pass before it is dropped - rather than the first sweep throwing away everything that
            // was just loaded
            this.lastNearby.put(new ChunkKey(chunk.world(), chunk.x(), chunk.z()), System.nanoTime());
            // Nothing has been shown to anybody yet, so there is nothing to put back to our own copy
            // and send is simply the difference
            // No entities: what is saved is the difference in blocks, and a frame is not a block. A
            // restored chunk draws its walls at once and its contents when it is read again, which is
            // the first time anybody looks at it
            this.mirrored.put(new ChunkKey(chunk.world(), chunk.x(), chunk.z()),
                new Mirrored(blocks, blocks, System.nanoTime(), true, chunk.publisherId(),
                    chunk.revision(), List.of()));
        }
        if (unknownBlocks > 0) {
            this.logger.warning("Dropped " + unknownBlocks + " saved mirror block(s) the game no longer "
                + "knows; those chunks will be right again as soon as they are read from their owner");
        }
        this.logger.info("Restored " + this.mirrored.size() + " mirrored chunk(s), " + blocksRestored
            + " block(s), from the last run");
    }

    /**
     * What is worth saving, copied on the main thread so the writing can be done off it.
     *
     * <p>Only chunks with something to draw. A stretch of border nobody has built along differs in
     * nothing, and writing out that it differs in nothing helps no one - it is re-fetched just the
     * same, and finding nothing is the cheap case.</p>
     */
    public List<MirrorStore.Saved> toSave() {
        final List<MirrorStore.Saved> saved = new ArrayList<>();
        for (final Map.Entry<ChunkKey, Mirrored> entry : this.mirrored.entrySet()) {
            final Map<Position, BlockData> blocks = entry.getValue().blocks();
            if (blocks.isEmpty()) {
                continue;
            }
            final List<MirrorStore.SavedBlock> savedBlocks = new ArrayList<>(blocks.size());
            for (final Map.Entry<Position, BlockData> block : blocks.entrySet()) {
                savedBlocks.add(new MirrorStore.SavedBlock(block.getKey().blockX(),
                    block.getKey().blockY(), block.getKey().blockZ(),
                    block.getValue().getAsString()));
            }
            saved.add(new MirrorStore.Saved(entry.getKey().world(), entry.getKey().x(),
                entry.getKey().z(), entry.getValue().publisherId(), entry.getValue().revision(),
                savedBlocks));
        }
        return saved;
    }

    /**
     * Forgets a player who has left, so their record of what they were shown does not outlive them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.shown.remove(event.getPlayer().getUniqueId());
        this.entities.forget(event.getPlayer());
    }

    /**
     * @param blocks what the owner has where we have something else
     * @param send the same, plus anything that was in the last picture and is not in this one, put
     *     back to this server's own copy so a demolished building does not stand forever
     * @param stale this picture is known to be behind - a change was announced while the chunk was
     *     being read, or an announcement was missed - so it is shown, but the chunk is read again on
     *     the next pass rather than waiting out the backstop
     * @param publisherId which run of the owning server numbered the announcements this picture counts
     * @param revision the last announcement about this chunk that is accounted for here. See
     *     {@link ChunkRevisions}
     * @param entities the owner's frames and stands in this chunk, drawn rather than sent as blocks
     *     because they are not blocks
     */
    private record Mirrored(Map<Position, BlockData> blocks, Map<Position, BlockData> send,
                            long fetchedAtNanos, boolean stale, String publisherId, long revision,
                            List<MirroredEntity> entities) {

        Mirrored readAgain() {
            return new Mirrored(this.blocks(), this.send(), this.fetchedAtNanos(), true,
                this.publisherId(), this.revision(), this.entities());
        }
    }
}
