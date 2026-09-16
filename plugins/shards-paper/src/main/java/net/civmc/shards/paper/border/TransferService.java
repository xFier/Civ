package net.civmc.shards.paper.border;

import java.util.Base64;
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
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.snapshot.PlayerSnapshots;
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
    private final Set<UUID> inTransit = ConcurrentHashMap.newKeySet();

    public TransferService(final JavaPlugin plugin, final ShardsClient client, final OwnedPlayers owned,
                           final Logger logger, final String serverName, final String failureMessage) {
        this.plugin = plugin;
        this.client = client;
        this.owned = owned;
        this.logger = logger;
        this.serverName = serverName;
        this.failureMessage = Component.text(failureMessage);
    }

    public boolean isInTransit(final UUID playerUuid) {
        return this.inTransit.contains(playerUuid);
    }

    /**
     * Sends a player to the shard owning {@code target}.
     *
     * <p>Must run on the main thread: the snapshot is read from a live player.</p>
     *
     * @return whether the transfer was started. False means the player stays exactly where they are
     */
    public boolean transfer(final Player player, final Location target) {
        final UUID playerUuid = player.getUniqueId();
        if (!this.inTransit.add(playerUuid)) {
            return false;
        }

        final PlayerAttemptLeaveShardEvent event = new PlayerAttemptLeaveShardEvent(player, target);
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

        final String payload;
        final PlayerLocation targetLocation;
        try {
            final PlayerSnapshot snapshot = PlayerSnapshots.capture(player);
            payload = Base64.getEncoder().encodeToString(PlayerSnapshotCodec.toBytes(snapshot));
            targetLocation = toPlayerLocation(target);
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not capture " + playerUuid + " for transfer", exception);
            this.inTransit.remove(playerUuid);
            return false;
        }
        if (targetLocation == null) {
            this.inTransit.remove(playerUuid);
            return false;
        }

        // Ownership is given up by the proxy as part of the transfer, so this server must stop
        // believing it holds them now - otherwise their quit would try to save over the destination
        this.owned.forget(playerUuid);
        this.client.transfer(PlayerTransferRequest.create(this.serverName, playerUuid, payload, targetLocation))
            // Back onto the main thread: the failure path kicks the player, and the transit set is
            // read by move handling that runs there
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(
                this.plugin, () -> complete(playerUuid, response, error)));
        return true;
    }

    private void complete(final UUID playerUuid, final PlayerTransferResponse response, final Throwable error) {
        this.inTransit.remove(playerUuid);
        final Player player = Bukkit.getPlayer(playerUuid);
        if (error != null) {
            failed(playerUuid, player, "the proxy could not be reached", error);
            return;
        }
        if (response.status() == TransferStatus.TRANSFERRED) {
            return;
        }
        if (response.status() == TransferStatus.NO_DESTINATION) {
            // Ground owned by nobody. A valid configuration, so the edge is a wall: they stay, still
            // owned here, and nothing was written
            this.owned.add(playerUuid);
            return;
        }
        failed(playerUuid, player, response.status() + ": " + response.failureMessage(), null);
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

    private static PlayerLocation toPlayerLocation(final Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }
}
