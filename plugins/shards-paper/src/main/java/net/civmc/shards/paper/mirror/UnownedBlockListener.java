package net.civmc.shards.paper.mirror;

import java.util.Iterator;
import java.util.List;
import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.StructureGrowEvent;

/**
 * Freezes this shard's copy of the ground it does not own.
 *
 * <p>The third side of the same problem as {@link UnownedEntityView} and {@link UnownedGroundListener}.
 * A shard loads and ticks the chunks past its own border, so its copy of a neighbour's land does not
 * merely sit there going out of date - it <strong>keeps changing on its own</strong>. Leaves decay,
 * fluids flow, grass and crops grow, ice forms and melts, fire spreads. None of it is real: the shard
 * that owns that ground has its own, different contents there, and this is a second set of events
 * happening to a copy nobody else can see.</p>
 *
 * <p>Two things come of stopping it, and the first is the one that matters.</p>
 *
 * <p><strong>It stops a copy acting across the border.</strong> Water in this server's copy of a
 * neighbour's valley flows downhill into ground this shard really does own, and floods it. A fire out
 * there spreads onto ground we do own. The blocks it arrives at are real, reinforceable, this
 * shard's - changed by a copy of somebody else's land that nobody else can see. That is the same
 * fault as a minecart rolling over the line, one layer below the entities.</p>
 *
 * <p><strong>It makes the copy stable.</strong> The mirror works by comparing what a neighbour sends
 * against this server's own copy, so a copy that keeps moving means a comparison that keeps growing,
 * and one worked out today cannot be relied on tomorrow. Frozen, this server's copy of foreign ground
 * is the same next week as it is now.</p>
 *
 * <p>Nothing is removed and nothing is corrected - the copy is left exactly as it is, simply no longer
 * moving. What a player <em>sees</em> out there is the neighbour's real ground, drawn over the top by
 * {@link MirrorView}; this only stops the thing underneath it from wandering.</p>
 *
 * <p>Refused at the block, not at the event, wherever an event covers several. An explosion or a tree
 * on this shard's own ground that reaches over the border keeps every block it is entitled to and
 * loses only the ones past the line, which is the rule the border itself follows.</p>
 *
 * <p>Redstone is deliberately left running, as it is for the announcements: a clock near a border
 * would otherwise be stopped for no visible reason, and the part of redstone that rewrites blocks -
 * pistons - is refused here anyway.</p>
 */
public final class UnownedBlockListener implements Listener {

    private final ShardBorder border;

    public UnownedBlockListener(final ShardBorder border) {
        this.border = border;
    }

    private boolean ours(final Block block) {
        return block == null || !this.border.isOutside(block.getX(), block.getZ());
    }

    /**
     * Refuses an event outright when the block it happens to is not ours.
     */
    private void refuseIfUnowned(final BlockEvent event, final Cancellable cancellable) {
        if (!ours(event.getBlock())) {
            cancellable.setCancelled(true);
        }
    }

    /**
     * Drops the blocks past the border from an event that covers several, leaving the rest to happen.
     */
    private void dropUnowned(final List<BlockState> blocks) {
        final Iterator<BlockState> states = blocks.iterator();
        while (states.hasNext()) {
            if (!ours(states.next().getBlock())) {
                states.remove();
            }
        }
    }

    private void dropUnownedBlocks(final List<Block> blocks) {
        blocks.removeIf(block -> !ours(block));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onLeavesDecay(final LeavesDecayEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onMoisture(final MoistureChangeEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFluidLevel(final FluidLevelChangeEvent event) {
        refuseIfUnowned(event, event);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
        if (!ours(event.getBlock())) {
            event.setCancelled(true);
        }
    }

    /**
     * Both ends, because this is the one that floods. A flow out of our ground onto a neighbour's
     * stops at the line rather than running down their valley in a copy only we can see, and a flow
     * the other way cannot arrive on ground we really do own.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        if (!ours(event.getBlock()) || !ours(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        refuseIfPistonReachesOut(event, event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        refuseIfPistonReachesOut(event, event.getBlock(), event.getBlocks(), event.getDirection());
    }

    /**
     * The whole event, not per block: half a piston's blocks moving and half staying would leave a
     * machine in a state the game never produces.
     */
    private void refuseIfPistonReachesOut(final Cancellable event, final Block piston,
                                          final List<Block> moving, final BlockFace direction) {
        if (!ours(piston)) {
            event.setCancelled(true);
            return;
        }
        for (final Block block : moving) {
            if (!ours(block) || !ours(block.getRelative(direction))) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        dropUnowned(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFertilize(final BlockFertilizeEvent event) {
        dropUnowned(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSponge(final SpongeAbsorbEvent event) {
        if (!ours(event.getBlock())) {
            event.setCancelled(true);
            return;
        }
        dropUnowned(event.getBlocks());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        dropUnownedBlocks(event.blockList());
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        dropUnownedBlocks(event.blockList());
    }
}
