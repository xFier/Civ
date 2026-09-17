package net.civmc.shards.paper.border;

import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.List;
import net.civmc.shards.api.TransferStatus;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockDropItemEvent;
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
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.util.Vector;

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
    private final BorderNotices notices;
    private final BorderOutlook outlook;

    public ShardBorderListener(final ShardBorder border, final TransferService transfers,
                               final BorderNotices notices, final BorderOutlook outlook) {
        this.border = border;
        this.transfers = transfers;
        this.notices = notices;
        this.outlook = outlook;
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
        if (refuseWithoutAsking(event.getPlayer(), to)) {
            event.setCancelled(true);
            return;
        }
        // Handed over first, cancelled second. Cancelling a move puts the player back where they
        // were, and their motion goes with it - so capturing after the cancel would carry a player
        // who is standing still, and a sprint jump over a border would stop dead on the far side.
        // Cancelled either way: they are held at the edge until the transfer answers, so they cannot
        // keep walking into ground this server is not authoritative for
        this.transfers.transferTo(event.getPlayer(), to);
        event.setCancelled(true);
    }

    /**
     * Turns back a step into ground no shard owns, without asking the proxy.
     *
     * <p>Only for ground owned by nobody, which is the one answer that cannot change while the server
     * is up: it comes from the shard map, and the shard map arrives once at startup. Anything else -
     * including a neighbour that is merely down - still goes to the proxy, because it is the proxy
     * that decides, and a shard coming back while somebody stands at its border is exactly the case
     * this must not get wrong.</p>
     *
     * <p>Worth the special case because the alternative is a broker round trip for every step taken
     * into a wall, and a player walking along one takes a great many. It also means the answer is
     * instant rather than arriving a moment after they have been pushed back.</p>
     */
    private boolean refuseWithoutAsking(final Player player, final Location to) {
        final boolean nowhereToGo = this.outlook.beyond(to.getBlockX(), to.getBlockZ())
            .map(BorderOutlook.Beyond::permanentlyClosed)
            .orElse(false);
        if (!nowhereToGo) {
            return false;
        }
        this.notices.refused(player, TransferStatus.NO_DESTINATION);
        return true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleMove(final VehicleMoveEvent event) {
        if (!this.border.isOutside(event.getTo())) {
            return;
        }
        boolean carryingSomebody = false;
        for (final Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                // The vehicle travels with them, described in the snapshot and rebuilt on the far
                // side. Anything else riding along does not - it is an entity of this shard with
                // nobody to carry it
                carryingSomebody = true;
                this.transfers.transferTo(player, event.getTo());
            }
        }
        if (carryingSomebody || this.border.isOutside(event.getFrom())) {
            return;
        }
        stopAtTheBorder(event.getVehicle(), event.getFrom());
    }

    /**
     * Stops a mob walking over the border.
     *
     * <p>Nothing carries an entity across a shard border: there is no transfer for one and no shard
     * asks for one, so a mob that walks over the line goes on existing here, on ground this server is
     * not authoritative for, invisible to the shard that is - and it is a real entity, so it eats, it
     * breeds and it can be killed for its drops by nobody at all.</p>
     *
     * <p>Only the crossing is refused, never a move that begins outside. Something already out there
     * is one of this shard's own and hauling it back in would drop a neighbour's field of animals into
     * our own - the same reason the unowned entity view hides rather than removes.</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityMove(final EntityMoveEvent event) {
        // Fired for every moving mob on the server, so this is the first thing asked and the cheapest
        if (!event.hasChangedBlock()) {
            return;
        }
        if (this.border.isOutside(event.getTo()) && !this.border.isOutside(event.getFrom())) {
            event.setCancelled(true);
        }
    }

    /**
     * Puts something back where it was, because its move cannot simply be refused.
     *
     * <p>{@link VehicleMoveEvent} has already happened by the time it is seen - it is a report, not a
     * request - so the only way to keep a minecart on this side of the line is to put it back and take
     * its speed away.</p>
     *
     * <p>Plainly, rather than with {@code TeleportFlag.EntityState.RETAIN_PASSENGERS}, which is marked
     * for removal. That leaves <strong>what happens to a non-player passenger unverified</strong> - a
     * mob in a minecart may be ejected here. The case this exists for is a cart or boat with nobody on
     * it, and guessing at a flag that is on its way out to cover a rarer one is how the last cosmetic
     * packet took the network down.</p>
     */
    private void stopAtTheBorder(final Entity entity, final Location wasAt) {
        entity.setVelocity(new Vector());
        entity.teleport(wasAt, PlayerTeleportEvent.TeleportCause.PLUGIN);
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

    /**
     * Ice, snow, concrete and the rest of what forms by itself. Missed when this list was written, and
     * the one most likely to be noticed: a whole winter's snow and ice across a neighbour's land, laid
     * down by this server alone.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onForm(final BlockFormEvent event) {
        cancelIfOutside(event.getBlock(), event);
    }

    /**
     * A tree. The sapling it grows from is refused by {@link #onGrow}, but a tree on our own ground
     * reaching over the line is not, so the blocks past it are dropped rather than the tree refused -
     * the same rule the explosions follow.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStructureGrow(final StructureGrowEvent event) {
        event.getBlocks().removeIf(this::isOutside);
    }

    /**
     * An entity rewriting a block: an enderman taking one, a falling block landing, a sheep eating
     * grass. Nothing of ours should be out there to do it - that is what the unowned entity view is
     * for - but this costs one comparison and closes the hole rather than trusting that.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityChangeBlock(final EntityChangeBlockEvent event) {
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
        cancelIfPistonReachesOut(event, event.getBlock(), event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPistonRetract(final BlockPistonRetractEvent event) {
        cancelIfPistonReachesOut(event, event.getBlock(), event.getBlocks(), event.getDirection());
    }

    /**
     * Where each block ends up, as well as where it starts, and the piston itself.
     *
     * <p>Checking only the blocks being moved misses the case that matters: every one of them is ours,
     * and they are all being pushed one block over the line onto ground that is not. It also missed a
     * piston sitting outside entirely, which nothing else here would have caught.</p>
     */
    private void cancelIfPistonReachesOut(final Cancellable event, final Block piston,
                                          final List<Block> moving, final BlockFace direction) {
        if (isOutside(piston)) {
            event.setCancelled(true);
            return;
        }
        for (final Block block : moving) {
            if (isOutside(block) || isOutside(block.getRelative(direction))) {
                event.setCancelled(true);
                return;
            }
        }
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

    private boolean isOutside(final Block block) {
        return this.border.isOutside(block.getX(), block.getZ());
    }

    private boolean isOutside(final BlockState state) {
        return this.border.isOutside(state.getX(), state.getZ());
    }
}
