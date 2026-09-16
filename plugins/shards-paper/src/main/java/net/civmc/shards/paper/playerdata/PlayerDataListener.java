package net.civmc.shards.paper.playerdata;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.PlayerClaimRequest;
import net.civmc.shards.api.PlayerClaimResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshotCodec;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.snapshot.PlayerSnapshots;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Takes ownership of a player's data as they connect and gives it up when they leave.
 *
 * <p>Ownership is taken at {@link AsyncPlayerPreLoginEvent} rather than on join. That event runs off
 * the main thread, so waiting on the proxy there costs nothing, and it happens before any player
 * object exists - which means a refusal is a login that never happened rather than a player who has
 * to be removed again.</p>
 */
public final class PlayerDataListener implements Listener {

    private static final long CLAIM_TIMEOUT_SECONDS = 10L;
    // A login can be allowed and then never complete - the client gives up, the connection drops. The
    // lock would otherwise be held by a server with nobody on it until that server next restarts
    private static final long JOIN_TIMEOUT_TICKS = 20L * 15L;

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final Logger logger;
    private final String serverName;
    private final Component failureMessage;

    private final Map<UUID, PlayerSnapshot> pendingSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLocation> pendingLocations = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> joinTimeouts = new ConcurrentHashMap<>();
    private final OwnedPlayers owned;

    public PlayerDataListener(final JavaPlugin plugin, final ShardsClient client, final String serverName,
                              final String failureMessage, final OwnedPlayers owned) {
        this.plugin = plugin;
        this.client = client;
        this.logger = plugin.getLogger();
        this.serverName = serverName;
        this.failureMessage = Component.text(failureMessage);
        this.owned = owned;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final UUID playerUuid = event.getUniqueId();
        final PlayerClaimResponse response;
        try {
            response = this.client.claim(PlayerClaimRequest.create(this.serverName, playerUuid))
                .get(CLAIM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            refuse(event, "interrupted while claiming player data", null);
            return;
        } catch (final ExecutionException | TimeoutException | RuntimeException exception) {
            refuse(event, "could not reach the proxy to claim player data", exception);
            return;
        }

        switch (response.status()) {
            case LOADED -> {
                // A null payload is a row that exists but has never been written back. Nothing to
                // restore, so the player keeps what is on disk - the same reading as NEW_PLAYER
                final PlayerSnapshot snapshot = response.payload() == null
                    ? null
                    : PlayerSnapshotCodec.fromBytes(Base64.getDecoder().decode(response.payload()));
                if (snapshot != null) {
                    this.pendingSnapshots.put(playerUuid, snapshot);
                }
                if (response.location() != null) {
                    this.pendingLocations.put(playerUuid, response.location());
                }
                take(playerUuid);
            }
            // Keep whatever is on disk. Treating this as an authoritative empty player would wipe
            // everyone at once the first time the table is empty
            case NEW_PLAYER -> take(playerUuid);
            case HELD_BY_OTHER -> refuse(event,
                "data still held by " + response.heldBy() + "; the previous server has not released it", null);
            case ERROR -> refuse(event, "the proxy refused the claim: " + response.failureMessage(), null);
            default -> refuse(event, "unknown claim status " + response.status(), null);
        }
    }

    @EventHandler
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        final UUID playerUuid = event.getConnection().getProfile().getId();
        final PlayerLocation stored = this.pendingLocations.remove(playerUuid);
        if (stored == null) {
            return;
        }
        final World world = Bukkit.getWorld(stored.world());
        if (world == null) {
            // The world they were last in does not exist here. Their own spawn logic is a better
            // answer than a coordinate in the wrong world
            this.logger.warning("No world named " + stored.world() + " for " + playerUuid
                + "; leaving them at the default spawn");
            return;
        }
        event.setSpawnLocation(new Location(world, stored.x(), stored.y(), stored.z()));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerUuid = player.getUniqueId();
        cancelJoinTimeout(playerUuid);
        final PlayerSnapshot snapshot = this.pendingSnapshots.remove(playerUuid);
        if (snapshot == null) {
            return;
        }
        try {
            PlayerSnapshots.restore(player, snapshot);
        } catch (final RuntimeException exception) {
            // Their stored state is on the proxy and was not consumed by a failed restore, so kicking
            // leaves it recoverable. Letting them play on half-restored state would not
            this.logger.log(Level.SEVERE, "Could not restore " + playerUuid, exception);
            player.kick(this.failureMessage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.owned.saveAndRelease(event.getPlayer());
    }

    private void take(final UUID playerUuid) {
        this.owned.add(playerUuid);
        final BukkitTask timeout = Bukkit.getScheduler().runTaskLater(this.plugin,
            () -> releaseAbandonedLogin(playerUuid), JOIN_TIMEOUT_TICKS);
        final BukkitTask previous = this.joinTimeouts.put(playerUuid, timeout);
        if (previous != null) {
            previous.cancel();
        }
    }

    private void releaseAbandonedLogin(final UUID playerUuid) {
        this.joinTimeouts.remove(playerUuid);
        if (Bukkit.getPlayer(playerUuid) != null) {
            return;
        }
        this.pendingSnapshots.remove(playerUuid);
        this.pendingLocations.remove(playerUuid);
        this.owned.releaseWithoutSaving(playerUuid);
    }

    private void cancelJoinTimeout(final UUID playerUuid) {
        final BukkitTask timeout = this.joinTimeouts.remove(playerUuid);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    private void refuse(final AsyncPlayerPreLoginEvent event, final String reason, final Throwable cause) {
        if (cause == null) {
            this.logger.warning("Refusing login of " + event.getUniqueId() + ": " + reason);
        } else {
            this.logger.log(Level.SEVERE, "Refusing login of " + event.getUniqueId() + ": " + reason, cause);
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, this.failureMessage);
    }
}
