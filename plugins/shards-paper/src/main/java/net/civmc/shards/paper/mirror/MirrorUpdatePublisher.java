package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.mirror.BlockUpdate;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Tells the other shards about blocks that have just changed near this one's border.
 *
 * <p>Without this the mirror can only notice a change by reading a chunk again, which meant re-reading
 * every chunk in sight every minute to discover that nothing had happened. Measured on the rig:
 * seventy chunks, every thirty seconds, for a player who was standing still.</p>
 *
 * <p><strong>Positions are recorded, not block states.</strong> Events disagree about whether they
 * carry the block before or after the change, and several of them - pistons, explosions, growth -
 * describe an intention rather than a result. Recording where something happened and reading the
 * actual block a tick later sidesteps all of it: what gets published is what is really there,
 * whatever the event meant, and whether or not something else cancelled it afterwards.</p>
 *
 * <p>Only blocks this shard owns, and only near its own outline. Describing a neighbour's ground would
 * be passing off this server's stale copy as truth, and a change a thousand blocks from any border is
 * one nobody can see from another shard.</p>
 *
 * <p>Redstone is deliberately absent. A single clock near a border would publish forever, and the
 * animated half of a world - redstone, and the rest of the ambience - is its own stage.</p>
 */
public final class MirrorUpdatePublisher implements Listener {

    // Generous: a neighbour on view-distance 10 reaches 160 blocks, and this costs only bandwidth,
    // since a receiver drops anything about a chunk it has not fetched
    private static final int PUBLISH_RADIUS = 256;
    // One tick of changes. A single piston or a big explosion is tens of blocks; anything beyond this
    // is a machine, and publishing all of it would be worse than letting the chunk fall stale until
    // the next full read
    private static final int MAX_PER_TICK = 2048;

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    private final ChunkRevisions revisions;
    // Positions changed this tick, read at the end of it. A set, so a block changed several times in
    // one tick is read once and published once
    private final Set<Block> dirty = new HashSet<>();
    private boolean warnedAboutFlood;
    // Blocks were dropped this tick, so what is published does not describe everything that changed
    private boolean lostBlocksThisTick;

    public MirrorUpdatePublisher(final ShardBorder border, final ShardsClient client, final String serverName,
                                 final Logger logger, final ChunkRevisions revisions) {
        this.border = border;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
        this.revisions = revisions;
    }

    /**
     * Reads everything that changed this tick and sends it. Main thread, once a tick.
     */
    public void flush() {
        if (this.dirty.isEmpty()) {
            return;
        }
        final Map<ChunkKey, List<BlockUpdate>> byChunk = new HashMap<>();
        for (final Block block : this.dirty) {
            final World world = block.getWorld();
            byChunk.computeIfAbsent(new ChunkKey(world.getName(), block.getX() >> 4, block.getZ() >> 4),
                    ignored -> new ArrayList<>())
                .add(new BlockUpdate(block.getX(), block.getY(), block.getZ(),
                    block.getBlockData().getAsString()));
        }
        this.dirty.clear();
        // Any chunk published on a tick that lost blocks may be missing some of them, and there is no
        // telling which: the cap is on the tick, not on one chunk. Numbering these with a gap has
        // every viewer read the chunk rather than accept a partial account of it as the whole story.
        // A chunk that lost *all* of its blocks is published not at all and still falls to the
        // backstop, which is what it did before this and is as far as a cap can be made honest
        final boolean partial = this.lostBlocksThisTick;
        this.lostBlocksThisTick = false;
        for (final Map.Entry<ChunkKey, List<BlockUpdate>> chunk : byChunk.entrySet()) {
            final long revision = partial
                ? this.revisions.nextWithGap(chunk.getKey())
                : this.revisions.next(chunk.getKey());
            this.client.publishMirrorUpdate(ChunkUpdateMessage.blocks(this.serverName, chunk.getKey().world(),
                chunk.getKey().x(), chunk.getKey().z(), chunk.getValue(), this.revisions.publisherId(),
                revision));
        }
    }

    /**
     * Notes that something happened here, if it is somewhere anyone else could be looking at.
     */
    private void changed(final Block block) {
        if (block == null || !this.border.isConfigured()) {
            return;
        }
        // Ours, and near enough to an edge that another shard could be showing it
        if (this.border.isOutside(block.getX(), block.getZ())
            || !this.border.outlineWithin(block.getX(), block.getZ(), PUBLISH_RADIUS)) {
            return;
        }
        if (this.dirty.size() >= MAX_PER_TICK) {
            this.lostBlocksThisTick = true;
            if (!this.warnedAboutFlood) {
                this.warnedAboutFlood = true;
                this.logger.warning("More than " + MAX_PER_TICK + " blocks changed near a border in one tick; "
                    + "the rest will not be published, and the chunks that were will be read again by "
                    + "whoever is looking at them");
            }
            return;
        }
        this.dirty.add(block);
    }

    private void changedAll(final Iterable<Block> blocks) {
        for (final Block block : blocks) {
            changed(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(final BlockPlaceEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFertilize(final BlockFertilizeEvent event) {
        for (final org.bukkit.block.BlockState state : event.getBlocks()) {
            changed(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        for (final org.bukkit.block.BlockState state : event.getBlocks()) {
            changed(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLeavesDecay(final LeavesDecayEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        changed(event.getBlock());
        changedAll(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        changedAll(event.blockList());
    }

    /**
     * Both ends of every block a piston moves: where it was and where it is going.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        changed(event.getBlock());
        for (final Block block : event.getBlocks()) {
            changed(block);
            changed(block.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        changed(event.getBlock());
        for (final Block block : event.getBlocks()) {
            changed(block);
            changed(block.getRelative(event.getDirection()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        changed(event.getBlock());
        changed(event.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFluidLevel(final FluidLevelChangeEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMoisture(final MoistureChangeEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSponge(final SpongeAbsorbEvent event) {
        changed(event.getBlock());
        for (final org.bukkit.block.BlockState state : event.getBlocks()) {
            changed(state.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(final SignChangeEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        changed(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        changed(event.getBlock());
    }
}
