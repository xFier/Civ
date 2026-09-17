package net.civmc.shards.paper.border;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.PlayerTransferRequest;
import net.civmc.shards.api.PlayerTransferResponse;
import net.civmc.shards.api.TransferStatus;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshotCodec;
import net.civmc.shards.api.snapshot.VehicleSnapshot;
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.snapshot.PlayerSnapshots;
import net.civmc.shards.paper.snapshot.Vehicles;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Hands a player to whichever shard owns the place they are going.
 *
 * <p>Every way of leaving a shard runs through here - walking over a border, and later the rocket,
 * cross-server teleport and pioneer paths - so there is one implementation of writing a player back,
 * giving up ownership and moving them, rather than one per reason for moving.</p>
 *
 * <p>The destination is not named here. The request carries where the player is heading and the proxy
 * works out whose ground that is, which keeps the shard map authoritative in one place.</p>
 */
public final class TransferService {

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final OwnedPlayers owned;
    private final Logger logger;
    private final String serverName;
    private final Component failureMessage;
    private final BorderNotices notices;
    private final BorderView view;
    private final Set<UUID> inTransit = ConcurrentHashMap.newKeySet();
    // What was taken out from under each player, so it can be put back if the handover never starts
    private final Map<UUID, VehicleSnapshot> removedVehicles = new ConcurrentHashMap<>();

    public TransferService(final JavaPlugin plugin, final ShardsClient client, final OwnedPlayers owned,
                           final Logger logger, final String serverName, final String failureMessage,
                           final BorderNotices notices, final BorderView view) {
        this.plugin = plugin;
        this.client = client;
        this.owned = owned;
        this.logger = logger;
        this.serverName = serverName;
        this.failureMessage = Component.text(failureMessage);
        this.notices = notices;
        this.view = view;
    }

    public boolean isInTransit(final UUID playerUuid) {
        return this.inTransit.contains(playerUuid);
    }

    /**
     * Forgets a player who has left, whether they were handed over or disconnected on the way.
     *
     * <p>This is what ends a successful handover: the answer comes back before the player's connection
     * actually moves, so they are held in transit until they are gone rather than from a reply that
     * only means their data arrived.</p>
     */
    public void forget(final UUID playerUuid) {
        this.inTransit.remove(playerUuid);
        this.removedVehicles.remove(playerUuid);
        this.notices.forget(playerUuid);
        this.view.forget(playerUuid);
    }

    /**
     * Sends a player to the shard owning {@code target}, arriving at that exact place.
     *
     * <p>Must run on the main thread: the snapshot is read from a live player.</p>
     *
     * @return whether the transfer was started. False means the player stays exactly where they are
     */
    public boolean transferTo(final Player player, final Location target) {
        return start(player, toPlayerLocation(target), target, null);
    }

    /**
     * Sends a player to a place on another shard, named by world and coordinates.
     *
     * <p>For a destination this server cannot express as a {@link Location} because the world belongs
     * to the other shard and does not exist here.</p>
     *
     * <p>Must run on the main thread: the snapshot is read from a live player.</p>
     */
    public boolean transferTo(final Player player, final PlayerLocation target) {
        return start(player, target, null, null);
    }

    /**
     * Sends a player to a named shard, letting that server decide where they appear.
     *
     * <p>For an arrival with no meaningful coordinate to aim at - the destination's own spawn logic
     * places them, as it does for anyone arriving there for the first time.</p>
     *
     * <p>The player's state travels exactly as it is. Anything that should not go with them has to be
     * taken off them <strong>before</strong> this is called, so that stays a rule of whatever is
     * moving them rather than something the transfer decides.</p>
     *
     * <p>Must run on the main thread: the snapshot is read from a live player.</p>
     */
    public boolean transferToShard(final Player player, final String shardName) {
        return start(player, null, null, shardName);
    }

    private boolean start(final Player player, final PlayerLocation target, final Location localTarget,
                          final String shardName) {
        final UUID playerUuid = player.getUniqueId();
        if (!this.inTransit.add(playerUuid)) {
            return false;
        }

        final PlayerAttemptLeaveShardEvent event = new PlayerAttemptLeaveShardEvent(player, localTarget);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            this.inTransit.remove(playerUuid);
            return false;
        }

        if (!this.owned.holds(playerUuid)) {
            // Nothing to hand over, because this server never took ownership. Sending them anyway
            // would have the destination claim a lock this one does not hold and read stale data
            this.logger.severe("Refusing to transfer " + playerUuid + ": their data is not owned here");
            this.inTransit.remove(playerUuid);
            return false;
        }

        final PlayerTransferRequest request;
        final VehicleSnapshot vehicle;
        final long capturedAt;
        final long startedAt = System.nanoTime();
        try {
            final PlayerSnapshot snapshot = PlayerSnapshots.captureForTransfer(player);
            capturedAt = System.nanoTime();
            vehicle = snapshot.vehicle();
            final String payload = Base64.getEncoder().encodeToString(PlayerSnapshotCodec.toBytes(snapshot));
            request = shardName == null
                ? PlayerTransferRequest.toLocation(this.serverName, playerUuid, payload, target)
                : PlayerTransferRequest.toShard(this.serverName, playerUuid, payload, shardName);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not prepare a transfer for " + playerUuid, exception);
            this.inTransit.remove(playerUuid);
            return false;
        }

        // What they were being shown belongs to the shard they are leaving, and the faces of it were
        // worked out for a block on this side. Particles expire on their own, but the outline behind
        // them would otherwise be handed to the arriving shard as though it were still true
        this.view.forget(playerUuid);

        // Taken out of this world before the handover is sent, not after it is confirmed. Between
        // sending and being told it worked the destination may already have rebuilt it, so removing
        // afterwards would leave one at each end - and a duplicated horse is worse than a horse that
        // briefly exists nowhere, which the failure path below puts back
        if (vehicle != null) {
            this.removedVehicles.put(playerUuid, vehicle);
            Vehicles.remove(player);
        }

        // Ownership is given up by the proxy as part of the transfer, so this server must stop
        // believing it holds them now - otherwise their quit would try to save over the destination
        this.owned.forget(playerUuid);
        this.client.transfer(request)
            // Back onto the main thread: the failure path kicks the player, and the transit set is
            // read by move handling that runs there
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(
                this.plugin, () -> {
                    this.logger.info(String.format(
                        "Transfer of %s: captured in %dms, proxy answered after %dms",
                        playerUuid, millis(startedAt, capturedAt), millis(capturedAt, System.nanoTime())));
                    complete(playerUuid, response, error);
                }));
        return true;
    }

    private void complete(final UUID playerUuid, final PlayerTransferResponse response, final Throwable error) {
        final Player player = Bukkit.getPlayer(playerUuid);
        if (error != null) {
            this.inTransit.remove(playerUuid);
            putVehicleBack(playerUuid, player);
            failed(playerUuid, player, "the proxy could not be reached", error);
            return;
        }
        if (response.status() == TransferStatus.TRANSFERRED) {
            // It travelled in the payload and the destination rebuilds it there
            this.removedVehicles.remove(playerUuid);
            // Still in transit until they actually go. The answer arrives before the connection is
            // handed over, so they keep moving here for a moment - and clearing it now would let those
            // moves start a second handover for a player this server has already given up
            return;
        }
        this.inTransit.remove(playerUuid);
        putVehicleBack(playerUuid, player);

        // Every one of these is refused before anything is written, so the player is untouched and
        // still owned here. The edge simply does not let them through this time - ground owned by
        // nobody, a shard that is not answering, or a save the proxy would not make
        if (response.status() == TransferStatus.NO_DESTINATION
            || response.status() == TransferStatus.DESTINATION_UNAVAILABLE
            || response.status() == TransferStatus.SAVE_REFUSED) {
            this.owned.add(playerUuid);
            this.logger.warning("Kept " + playerUuid + " here: " + response.status() + " ("
                + response.failureMessage() + ")");
            if (player != null) {
                // Otherwise the only sign is being shoved back a block, which reads as the server
                // being broken rather than as the border doing what it is for
                this.notices.refused(player, response.status());
            }
            return;
        }

        // ERROR only. Whether the save landed is exactly what is unknown, so this server must not
        // carry on playing them - a second copy would write over one the proxy may already hold
        failed(playerUuid, player, response.status() + ": " + response.failureMessage(), null);
    }

    /**
     * Rebuilds a vehicle that was taken away for a handover that then did not happen.
     *
     * <p>If the player has already gone there is nothing to seat, and the vehicle is reported rather
     * than rebuilt riderless in a world they are not in - it is recoverable from the log, which a
     * silently dropped horse is not.</p>
     */
    private void putVehicleBack(final UUID playerUuid, final Player player) {
        final VehicleSnapshot vehicle = this.removedVehicles.remove(playerUuid);
        if (vehicle == null) {
            return;
        }
        if (player == null) {
            this.logger.severe("Lost the " + vehicle.type() + " " + playerUuid
                + " was riding: their transfer failed after it was removed and they are no longer here");
            return;
        }
        try {
            Vehicles.restore(player, vehicle);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not give " + playerUuid + " back the " + vehicle.type()
                + " they were riding", exception);
        }
    }


    private void failed(final UUID playerUuid, final Player player, final String reason, final Throwable error) {
        if (error == null) {
            this.logger.severe("Transfer of " + playerUuid + " failed: " + reason);
        } else {
            this.logger.log(Level.SEVERE, "Transfer of " + playerUuid + " failed: " + reason, error);
        }
        if (player == null) {
            return;
        }
        // Whether the save went through is exactly what is unknown here, so this server must not keep
        // playing them: a second copy would write over the one the proxy may already hold
        player.kick(this.failureMessage);
    }

    static long millis(final long fromNanos, final long toNanos) {
        return (toNanos - fromNanos) / 1_000_000L;
    }

    private static PlayerLocation toPlayerLocation(final Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }
}
