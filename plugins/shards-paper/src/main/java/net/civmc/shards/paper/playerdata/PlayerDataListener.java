package net.civmc.shards.paper.playerdata;

import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.PlayerClaimRequest;
import net.civmc.shards.api.PlayerClaimResponse;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshotCodec;
import net.civmc.shards.paper.border.ArrivalCue;
import net.civmc.shards.paper.border.TransferService;
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
    // Distinct from the general failure message: nothing is wrong with this player's data, the server
    // simply is not ready for anyone yet, and it says so rather than implying their data is at risk
    private static final Component NOT_READY_MESSAGE =
        Component.text("This shard is still starting up. Please reconnect in a moment.");

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final Logger logger;
    private final String serverName;
    private final Component failureMessage;

    private final Map<UUID, PlayerSnapshot> pendingSnapshots = new ConcurrentHashMap<>();
    private final Map<UUID, PlayerLocation> pendingLocations = new ConcurrentHashMap<>();
    private final Map<UUID, BukkitTask> joinTimeouts = new ConcurrentHashMap<>();
    // Only for the timing line at join. Kept apart from the state above so it can be read and
    // discarded without touching anything that matters
    private final Map<UUID, Long> preLoginDoneAt = new ConcurrentHashMap<>();
    private final Map<UUID, Long> spawnLocationAt = new ConcurrentHashMap<>();
    private final OwnedPlayers owned;
    private final TransferService transfers;
    private final BooleanSupplier startupComplete;
    private final ArrivalCue arrivalCue;
    // Told to us by the proxy, which is the only side that can tell a crossing from a login: both
    // claim a lock and restore a snapshot, and from here they are identical
    private final Set<UUID> arriving = ConcurrentHashMap.newKeySet();

    public PlayerDataListener(final JavaPlugin plugin, final ShardsClient client, final String serverName,
                              final String failureMessage, final OwnedPlayers owned,
                              final TransferService transfers, final BooleanSupplier startupComplete,
                              final ArrivalCue arrivalCue) {
        this.plugin = plugin;
        this.client = client;
        this.logger = plugin.getLogger();
        this.serverName = serverName;
        this.failureMessage = Component.text(failureMessage);
        this.owned = owned;
        this.transfers = transfers;
        this.startupComplete = startupComplete;
        this.arrivalCue = arrivalCue;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPreLogin(final AsyncPlayerPreLoginEvent event) {
        final UUID playerUuid = event.getUniqueId();
        // Before the claim, because this is not about the player. Until the startup handshake has been
        // answered this server does not know which ground it owns, and a border that owns nowhere owns
        // everywhere - so letting someone in would put them on a shard with no edge enforced at all
        if (!this.startupComplete.getAsBoolean()) {
            refuse(event, "the startup handshake has not completed, so this server has no shard areas yet",
                null, NOT_READY_MESSAGE);
            return;
        }
        final long askedAt = System.nanoTime();
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

        this.logger.info(String.format("Claim of %s answered in %dms (%s)", playerUuid,
            elapsedMillis(askedAt), response.status()));

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
                if (response.arriving()) {
                    this.arriving.add(playerUuid);
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
        this.preLoginDoneAt.put(playerUuid, System.nanoTime());
    }

    @EventHandler
    public void onSpawnLocation(final AsyncPlayerSpawnLocationEvent event) {
        final UUID playerUuid = event.getConnection().getProfile().getId();
        // Recorded before anything else here, and whether or not there is a location to apply: this
        // event is the first moment after the client has finished reconfiguring, so the gap since
        // pre-login is what that cost
        this.spawnLocationAt.put(playerUuid, System.nanoTime());
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
        // Orientation comes from the snapshot, not the stored coordinates: arriving without it turns
        // every border crossing into being spun round to face south
        final PlayerSnapshot snapshot = this.pendingSnapshots.get(playerUuid);
        final float yaw = snapshot == null ? 0.0f : snapshot.yaw();
        final float pitch = snapshot == null ? 0.0f : snapshot.pitch();
        event.setSpawnLocation(new Location(world, stored.x(), stored.y(), stored.z(), yaw, pitch));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID playerUuid = player.getUniqueId();
        cancelJoinTimeout(playerUuid);
        reportLoginTiming(playerUuid);
        final boolean crossedIn = this.arriving.remove(playerUuid);
        final PlayerSnapshot snapshot = this.pendingSnapshots.remove(playerUuid);
        if (snapshot == null) {
            return;
        }
        final long restoreStartedAt = System.nanoTime();
        try {
            PlayerSnapshots.restore(player, snapshot);
            this.logger.info(String.format("Restored %s in %dms", playerUuid,
                elapsedMillis(restoreStartedAt)));
            // A tick later: the join tick sends the player their position, which discards any velocity
            // set during it, and gliding is refused until the elytra from the restore above is on
            Bukkit.getScheduler().runTask(this.plugin, () -> {
                if (player.isOnline()) {
                    PlayerSnapshots.restoreMotion(player, snapshot);
                }
            });
            // Only for a crossing. A login already looks like a login, and saying "you have arrived"
            // to someone who simply connected would be telling them about a shard boundary they did
            // not cross
            if (crossedIn) {
                this.arrivalCue.show(player);
            }
        } catch (final RuntimeException exception) {
            // Their stored state is on the proxy and was not consumed by a failed restore, so kicking
            // leaves it recoverable. Letting them play on half-restored state would not
            this.logger.log(Level.SEVERE, "Could not restore " + playerUuid, exception);
            player.kick(this.failureMessage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        // Does nothing for a player handed to another shard: ownership was given up when they were
        // handed over, and saving again here would write over what they are doing now
        this.owned.saveAndRelease(event.getPlayer());
        this.transfers.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Where the pause between shards actually goes.
     *
     * <p>Pre-login to spawn location spans the client leaving play, being sent the registries and tags,
     * rebuilding them and saying it is ready - the server is mostly idle waiting through it. Spawn
     * location to join is this server putting the player into the world. Anything after join, such as
     * terrain appearing, is on the client and invisible from here.</p>
     */
    private void reportLoginTiming(final UUID playerUuid) {
        final Long preLogin = this.preLoginDoneAt.remove(playerUuid);
        final Long spawnLocation = this.spawnLocationAt.remove(playerUuid);
        if (preLogin == null || spawnLocation == null) {
            return;
        }
        final long now = System.nanoTime();
        this.logger.info(String.format(
            "Login of %s: %dms reconfiguring the client, %dms placing them, %dms from claim to join",
            playerUuid,
            (spawnLocation - preLogin) / 1_000_000L,
            (now - spawnLocation) / 1_000_000L,
            (now - preLogin) / 1_000_000L));
    }

    private static long elapsedMillis(final long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000L;
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
        this.arriving.remove(playerUuid);
        this.preLoginDoneAt.remove(playerUuid);
        this.spawnLocationAt.remove(playerUuid);
        this.owned.releaseWithoutSaving(playerUuid);
    }

    private void cancelJoinTimeout(final UUID playerUuid) {
        final BukkitTask timeout = this.joinTimeouts.remove(playerUuid);
        if (timeout != null) {
            timeout.cancel();
        }
    }

    private void refuse(final AsyncPlayerPreLoginEvent event, final String reason, final Throwable cause) {
        refuse(event, reason, cause, this.failureMessage);
    }

    private void refuse(final AsyncPlayerPreLoginEvent event, final String reason, final Throwable cause,
                        final Component message) {
        if (cause == null) {
            this.logger.warning("Refusing login of " + event.getUniqueId() + ": " + reason);
        } else {
            this.logger.log(Level.SEVERE, "Refusing login of " + event.getUniqueId() + ": " + reason, cause);
        }
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, message);
    }
}
