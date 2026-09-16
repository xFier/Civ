package net.civmc.shards.paper.border;

import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;

/**
 * Stops this shard at its edges, and hands over anyone who walks past one.
 *
 * <p>The world does not stop at a border - it keeps generating, and the blocks past the edge belong to
 * another shard that is authoritative for them. Everything that could act on one of those blocks has
 * to be refused here, or the seam is visible and exploitable: you could build across it, pipe items
 * through it, or run redstone into ground this server does not own and the owning shard never sees.
 * That is why this class is mostly a long list of small handlers.</p>
 */
public final class ShardBorderListener implements Listener {

    private final ShardBorder border;
    private final TransferService transfers;

    public ShardBorderListener(final ShardBorder border, final TransferService transfers) {
        this.border = border;
        this.transfers = transfers;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        // Only when the block changes: this runs for every fraction of a block a player moves, and
        // ownership cannot change without leaving the block you were standing on
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        if (!this.border.isOutside(to)) {
            return;
        }
        // Cancelled either way. The player is held at the edge until the transfer answers, so they
        // cannot keep walking into ground this server is not authoritative for
        event.setCancelled(true);
        this.transfers.transferTo(event.getPlayer(), to);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleMove(final VehicleMoveEvent event) {
        if (!this.border.isOutside(event.getTo())) {
            return;
        }
        for (final Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                // The vehicle travels with them, described in the snapshot and rebuilt on the far
                // side. Anything else riding along does not - it is an entity of this shard with
                // nobody to carry it
                this.transfers.transferTo(player, event.getTo());
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(final PlayerTeleportEvent event) {
        // Ender pearls and chorus fruit can put a player past the edge without a move event ever
        // covering the ground between
        if (this.border.isOutside(event.getTo())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(final BlockPlaceEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(final BlockBreakEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.hasBlock() && this.border.isOutside(event.getClickedBlock().getLocation())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBurn(final BlockBurnEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onIgnite(final BlockIgniteEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFade(final BlockFadeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onGrow(final BlockGrowEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFertilize(final BlockFertilizeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeavesDecay(final LeavesDecayEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDispense(final BlockDispenseEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(final BlockDropItemEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMoisture(final MoistureChangeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCauldronLevel(final CauldronLevelChangeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFluidLevel(final FluidLevelChangeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(final SignChangeEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSpongeAbsorb(final SpongeAbsorbEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFromTo(final BlockFromToEvent event) {
        // Both ends: water inside the shard must not flow out, and water outside must not flow in
        if (isOutside(event.getBlock()) || isOutside(event.getToBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onRedstone(final BlockRedstoneEvent event) {
        if (isOutside(event.getBlock())) {
            // Not cancellable, so the change is neutralised by holding the current where it was
            event.setNewCurrent(event.getOldCurrent());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonExtend(final BlockPistonExtendEvent event) {
        cancelIfAnyOutside(event.getBlocks(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        cancelIfAnyOutside(event.getBlocks(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockExplode(final BlockExplodeEvent event) {
        // The blocks outside are dropped from the list rather than the whole explosion being
        // cancelled, so an explosion near a border still works on the side that belongs to us
        event.blockList().removeIf(this::isOutside);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityExplode(final EntityExplodeEvent event) {
        event.blockList().removeIf(this::isOutside);
    }

    private void cancelIfOutside(final Block block, final org.bukkit.event.Cancellable event) {
        if (isOutside(block)) {
            event.setCancelled(true);
        }
    }

    private void cancelIfAnyOutside(final Iterable<Block> blocks, final org.bukkit.event.Cancellable event) {
        for (final Block block : blocks) {
            if (isOutside(block)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean isOutside(final Block block) {
        return this.border.isOutside(block.getX(), block.getZ());
    }

    private boolean isOutside(final BlockState state) {
        return this.border.isOutside(state.getX(), state.getZ());
    }
}
