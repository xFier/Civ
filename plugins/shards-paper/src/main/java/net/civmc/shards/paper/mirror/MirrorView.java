package net.civmc.shards.paper.mirror;

import io.papermc.paper.math.Position;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
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
import net.civmc.shards.api.mirror.ChunkSectionState;
import net.civmc.shards.api.mirror.ChunkState;
import net.civmc.shards.api.mirror.ChunkStateCodec;
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

    // How long a fetched chunk is trusted before being asked for again. Crude, and the reason live
    // updates are the next slice: until they land this is the only thing that notices a neighbour
    // building something
    private static final long FRESH_FOR_NANOS = TimeUnit.SECONDS.toNanos(60L);

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final BorderOutlook outlook;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    private final int radiusChunks;
    private final MirrorMetrics metrics;

    // One copy per chunk, shared by everybody on this server. A hundred players at one seam ask for
    // the same ground, and it is the same answer for all of them
    private final Map<ChunkKey, Mirrored> mirrored = new ConcurrentHashMap<>();
    private final Set<ChunkKey> inFlight = ConcurrentHashMap.newKeySet();
    // Which chunks each player has been sent, so a diff is sent once rather than every pass. Dropped
    // when a chunk leaves their range, because their client discards it and will be sent this
    // server's own version again if they come back
    private final Map<UUID, Set<ChunkKey>> shown = new ConcurrentHashMap<>();

    public MirrorView(final JavaPlugin plugin, final ShardBorder border, final BorderOutlook outlook,
                      final ShardsClient client, final String serverName, final Logger logger,
                      final int radiusChunks, final MirrorMetrics metrics) {
        this.plugin = plugin;
        this.border = border;
        this.outlook = outlook;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
        this.radiusChunks = radiusChunks;
        this.metrics = metrics;
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
        if (current == null || System.nanoTime() - current.fetchedAtNanos() > FRESH_FOR_NANOS) {
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
        this.client.chunkState(owner.get().shardName(),
                ChunkStateRequest.create(this.serverName, key.world(), key.x(), key.z()))
            .whenComplete((response, error) -> accept(key, response, error));
    }

    private void accept(final ChunkKey key, final ChunkStateResponse response, final Throwable error) {
        try {
            if (error != null || response == null || response.failed()) {
                // Left showing our own copy. A neighbour that cannot answer is not a reason to draw a
                // hole in the world, and the fetch is retried on the next pass
                this.logger.log(Level.FINE, "Could not read " + key + " from its owner", error);
                return;
            }
            final ChunkState state = ChunkStateCodec.fromBytes(Base64.getDecoder().decode(response.state()));
            diff(key, state).thenAccept(blocks -> Bukkit.getScheduler().runTask(this.plugin,
                () -> install(key, blocks)));
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
    private void install(final ChunkKey key, final Map<Position, BlockData> blocks) {
        final Mirrored previous = this.mirrored.get(key);
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
        this.mirrored.put(key, new Mirrored(blocks, send, System.nanoTime()));
        if (previous != null && previous.blocks().equals(blocks)) {
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

    private void forgetOutOfRange(final Player viewer, final Set<ChunkKey> inRange) {
        final Set<ChunkKey> alreadyShown = this.shown.get(viewer.getUniqueId());
        if (alreadyShown != null) {
            alreadyShown.retainAll(inRange);
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
     * Forgets a player who has left, so their record of what they were shown does not outlive them.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.shown.remove(event.getPlayer().getUniqueId());
    }

    /**
     * @param blocks what the owner has where we have something else
     * @param send the same, plus anything that was in the last picture and is not in this one, put
     *     back to this server's own copy so a demolished building does not stand forever
     */
    private record Mirrored(Map<Position, BlockData> blocks, Map<Position, BlockData> send,
                            long fetchedAtNanos) {
    }
}
