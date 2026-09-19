package net.civmc.shards.paper.border;

import io.papermc.paper.event.entity.EntityMoveEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import net.civmc.shards.api.TransferStatus;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
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
import org.bukkit.event.player.PlayerQuitEvent;
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

    // Somebody walking a border generates this every tick, and the answer does not change between
    // two of them. Once a second is enough to read afterwards and little enough to leave switched on
    private static final long SAY_SO_EVERY_NANOS = TimeUnit.SECONDS.toNanos(1L);
    // How long somebody may walk at a seam without reaching it before they are handed over anyway.
    // Half a second: long enough that anybody actually walking there arrives first and this never
    // fires, short enough that being stuck does not read as the border being broken
    private static final long STUCK_AFTER_NANOS = TimeUnit.MILLISECONDS.toNanos(500L);

    private final ShardBorder border;
    private final TransferService transfers;
    private final BorderNotices notices;
    private final BorderOutlook outlook;
    private final FarSideBlocks farSide;
    private final Logger logger;
    private final Map<UUID, Long> saidSoAt = new ConcurrentHashMap<>();
    // Since when each player has been walking at a seam without getting over it
    private final Map<UUID, Long> pressing = new ConcurrentHashMap<>();

    public ShardBorderListener(final ShardBorder border, final TransferService transfers,
                               final BorderNotices notices, final BorderOutlook outlook,
                               final FarSideBlocks farSide, final Logger logger) {
        this.border = border;
        this.transfers = transfers;
        this.notices = notices;
        this.outlook = outlook;
        this.farSide = farSide;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(final PlayerMoveEvent event) {
        if (this.border.isOutside(event.getTo())) {
            // They have crossed the line itself, and where they arrive is where they already are
            this.pressing.remove(event.getPlayer().getUniqueId());
            hand(event, event.getTo());
            return;
        }
        pressedAgainstTheLine(event);
    }

    /**
     * Hands over somebody who is on their way off this shard, or refuses them.
     */
    private void hand(final PlayerMoveEvent event, final Location leaving) {
        if (refuseWithoutAsking(event.getPlayer(), leaving) || walledOff(event.getPlayer(), leaving)) {
            event.setCancelled(true);
            return;
        }
        // Handed over first, cancelled second. Cancelling a move puts the player back where they
        // were, and their motion goes with it - so capturing after the cancel would carry a player
        // who is standing still, and a sprint jump over a border would stop dead on the far side.
        // Their real momentum, which is not what the server holds as their velocity: a player is
        // simulated by their own client, so this server's idea of how fast they are going is whatever
        // last pushed them, and for somebody simply running that is nothing. One tick of movement is
        // the same unit velocity is measured in, so the difference between the two ends of this move
        // is the thing to carry
        final Vector momentum = event.getTo().toVector().subtract(event.getFrom().toVector());
        if (shouldSaySo(event.getPlayer())) {
            this.logger.info(String.format(
                "%s is crossing at %d,%d carrying %.4f,%.4f,%.4f (this server held them at %.4f,%.4f,%.4f)",
                event.getPlayer().getName(), leaving.getBlockX(), leaving.getBlockZ(),
                momentum.getX(), momentum.getY(), momentum.getZ(),
                event.getPlayer().getVelocity().getX(), event.getPlayer().getVelocity().getY(),
                event.getPlayer().getVelocity().getZ()));
        }
        this.transfers.transferTo(event.getPlayer(), leaving, momentum);
        event.setCancelled(true);
    }

    /**
     * Hands over somebody who has been walking at a seam and has not managed to cross it.
     *
     * <p>Crossing is the line itself now, which is only reachable when both servers agree about the
     * ground on the far side of it. They nearly always do - the band past every border is read from
     * its owner at startup - but a neighbour that was down then leaves a strip of this server's own
     * copy standing, and where that copy holds a hill the neighbour levelled years ago, a player is
     * stopped a third of a block short of the line and can never reach it. That is not a rare corner:
     * it is what walking into a border did all morning, and it reads as the border being broken.
     *
     * <p>So pressing at a seam for half a second and getting nowhere is a crossing too. Time rather
     * than a count of moves, because a player held against something sends very few of them - and
     * deliberately blunt, because it does not need to know <em>why</em> they are stuck. An unsynced
     * strip, a chunk the mirror has not read, a race, or something neither of us has thought of all
     * come out here.
     *
     * <p>It cannot be used to walk through anything: {@link #walledOff} runs first, so a seam the
     * neighbour really has built across refuses the crossing and this never starts counting. What is
     * left is only ever somebody who should be able to cross and is not managing to.
     */
    private void pressedAgainstTheLine(final PlayerMoveEvent event) {
        final Location target = SeamCrossing.reached(this.border, event.getFrom(), event.getTo());
        if (target == null) {
            this.pressing.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (cannotBeCrossed(target)) {
            // There is nowhere to go, so they are not stuck and nothing is counted. Their move is
            // left alone: this runs while they are still walking on ground this shard owns, and
            // cancelling here stopped them a whole block short of their own border - they could not
            // even stand on the last block inside it. Whatever is in the way is in the way at the
            // line, and that is where they should meet it
            this.pressing.remove(event.getPlayer().getUniqueId());
            return;
        }
        final Long since = this.pressing.putIfAbsent(event.getPlayer().getUniqueId(), System.nanoTime());
        if (since == null || System.nanoTime() - since < STUCK_AFTER_NANOS) {
            // Left to walk. Nothing is cancelled here: the line is a block away and reaching it is
            // exactly what is meant to happen
            return;
        }
        this.pressing.remove(event.getPlayer().getUniqueId());
        this.logger.info(event.getPlayer().getName() + " could not reach the seam at " + target.getBlockX()
            + "," + target.getBlockZ() + " within " + (STUCK_AFTER_NANOS / 1_000_000L) + "ms, so they are "
            + "being handed over from where they are. This server's copy of the far side is out of date");
        hand(event, target);
    }

    /**
     * Whether this player's border has been reported on recently. Walking into one is a line a tick
     * otherwise, and the answer does not change between two of them.
     */
    private boolean shouldSaySo(final Player player) {
        final long now = System.nanoTime();
        final Long lastSaid = this.saidSoAt.get(player.getUniqueId());
        if (lastSaid != null && now - lastSaid < SAY_SO_EVERY_NANOS) {
            return false;
        }
        this.saidSoAt.put(player.getUniqueId(), now);
        return true;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.saidSoAt.remove(event.getPlayer().getUniqueId());
        this.pressing.remove(event.getPlayer().getUniqueId());
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
        if (!nowhereToGo(to)) {
            return false;
        }
        // The last cancel here that said nothing at all. It is the ordinary answer at the edge of the
        // map, so it is not a fault - but it is indistinguishable in the game from the border being
        // broken, and it cost two runs to find that out
        if (shouldSaySo(player)) {
            this.logger.info(player.getName() + " was turned back at " + to.getBlockX() + ","
                + to.getBlockZ() + " without asking the proxy: nothing owns the ground there");
        }
        this.notices.refused(player, TransferStatus.NO_DESTINATION);
        return true;
    }

    /**
     * Turns back a crossing into ground the neighbour has built on, so that a wall is a wall.
     *
     * <p>Without this a border is a way through anything. A player who arrives inside a block is
     * lifted clear of it by the destination, which exists for the case where the two servers disagree
     * about what is there - and used on a wall that both servers agree about, it is a door: walk at
     * it, be handed over into it, and be placed on the far side of it. A seam runs through everything
     * eventually, so that is every wall, roof and sealed room along one.</p>
     *
     * <p>So the far side is asked first, and a crossing into something solid is refused exactly as
     * walking into it would be: the move is cancelled, no handover starts, and the player stops. From
     * in the game there is no border there at all, which is the point - the wall is the wall.</p>
     *
     * <p>Only where the answer is known. A chunk this server has not read yet answers null, and a
     * guess either way is worse than the old behaviour: refusing would wall players in at a border
     * they could cross a second later, and the destination's own check still catches the rest.</p>
     */
    private boolean walledOff(final Player player, final Location leaving) {
        if (!farSideIsSolid(leaving)) {
            return false;
        }
        if (shouldSaySo(player)) {
            this.logger.info(player.getName() + " was stopped at the border by what the neighbour has "
                + "at " + leaving.getBlockX() + "," + leaving.getBlockY() + "," + leaving.getBlockZ()
                + ", so no crossing was started");
        }
        return true;
    }

    /**
     * Whether there is any point handing somebody towards this place, asked without saying anything.
     *
     * <p>The quiet half of the two refusals. They are answers to something a player has just done, so
     * they log and they write in the action bar - which is right at the moment of a crossing and
     * wrong while somebody is merely walking towards one, where the same question is asked every
     * move.
     */
    private boolean cannotBeCrossed(final Location target) {
        return nowhereToGo(target) || farSideIsSolid(target);
    }

    private boolean nowhereToGo(final Location to) {
        return this.outlook.beyond(to.getBlockX(), to.getBlockZ())
            .map(BorderOutlook.Beyond::permanentlyClosed)
            .orElse(false);
    }

    /**
     * Whether the shard that owns the far side has something standing where they would arrive.
     *
     * <p>Two blocks, because that is what a player is, and passability rather than solidity so that
     * crossing into water or long grass is not treated as crossing into stone. Unknown ground - a
     * chunk this server has not read - is not solid: guessing that way would wall players in at a
     * border they could cross a moment later.
     */
    private boolean farSideIsSolid(final Location target) {
        final int feet = target.getBlockY();
        final BlockData atFeet = this.farSide.at(target.getWorld(), target.getBlockX(), feet,
            target.getBlockZ());
        final BlockData atHead = this.farSide.at(target.getWorld(), target.getBlockX(), feet + 1,
            target.getBlockZ());
        if (atFeet == null || atHead == null) {
            return false;
        }
        return atFeet.getMaterial().isSolid() || atHead.getMaterial().isSolid();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onVehicleMove(final VehicleMoveEvent event) {
        if (!this.border.isOutside(event.getTo())) {
            handOverBeforeTheTrackRunsOut(event);
            return;
        }
        boolean carryingSomebody = false;
        for (final Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                if (walledOff(player, event.getTo())) {
                    // The rider stays where they are, and so does what is carrying them: a wall the
                    // neighbour really has is a wall from a minecart too
                    stopAtTheBorder(event.getVehicle(), event.getFrom());
                    return;
                }
                // The vehicle travels with them, described in the snapshot and rebuilt on the far
                // side. Anything else riding along does not - it is an entity of this shard with
                // nobody to carry it
                carryingSomebody = true;
                this.transfers.transferTo(player, event.getTo(),
                    event.getTo().toVector().subtract(event.getFrom().toVector()));
            }
        }
        if (carryingSomebody || this.border.isOutside(event.getFrom())) {
            return;
        }
        stopAtTheBorder(event.getVehicle(), event.getFrom());
    }

    /**
     * Hands over a rider one block before the border, because a minecart cannot reach it.
     *
     * <p>Everything else crosses by arriving on the far side and being caught there: a player walks
     * over the line, a boat floats over it, a horse steps over it. <strong>A minecart cannot.</strong>
     * The rail it runs on is a block, and a block past the border belongs to the neighbour - it is
     * drawn here and is not here, and placing one is refused, so the track can never really continue
     * on this side. The cart reaches the last rail, comes off the end and stops, and the move that
     * would have been refused and turned into a handover never happens. Riding a minecart to another
     * shard was simply impossible.</p>
     *
     * <p>So a vehicle carrying somebody is handed over from the last block <em>inside</em>, looking
     * one block along the way it is already travelling. Only on the move that takes it into a new
     * block, so this is about a vehicle that is going somewhere rather than one rocking on the spot,
     * and the direction is taken from the move itself rather than from velocity, which is where the
     * cart wants to go rather than where it is going.</p>
     *
     * <p>The rider arrives at that next block, which is the first one the neighbour owns, and their
     * vehicle is rebuilt around them there as it is for any other crossing. Its speed does not go with
     * it - a rebuilt cart starts still - and it is left that way rather than guessed at.</p>
     */
    private void handOverBeforeTheTrackRunsOut(final VehicleMoveEvent event) {
        final Location to = event.getTo();
        final Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        if (event.getVehicle().getPassengers().isEmpty()) {
            return;
        }
        final Location next = to.clone().add(Integer.signum(to.getBlockX() - from.getBlockX()), 0,
            Integer.signum(to.getBlockZ() - from.getBlockZ()));
        if (!this.border.isOutside(next)) {
            return;
        }
        final Vector momentum = to.toVector().subtract(from.toVector());
        for (final Entity passenger : event.getVehicle().getPassengers()) {
            if (passenger instanceof Player player) {
                if (walledOff(player, next)) {
                    // Handing them over a block early is what makes rails cross at all, and it is also
                    // what walks a rider straight into the neighbour's wall a block before they could
                    // have seen it stop them. The cart stops on the last rail instead
                    stopAtTheBorder(event.getVehicle(), from);
                    return;
                }
                this.transfers.transferTo(player, next, momentum);
            }
        }
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
