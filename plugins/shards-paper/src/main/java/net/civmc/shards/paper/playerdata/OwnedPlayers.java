package net.civmc.shards.paper.playerdata;

import java.util.Base64;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.PlayerCheckpointRequest;
import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.api.PlayerReleaseRequest;
import net.civmc.shards.api.PlayerSaveRequest;
import net.civmc.shards.api.PlayerSaveResponse;
import net.civmc.shards.api.SaveStatus;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshotCodec;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.snapshot.PlayerSnapshots;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The players whose data this server currently owns, and the two ways it gives that ownership up.
 *
 * <p>Held separately from the listener because a transfer also has to give ownership up, at a moment
 * that has nothing to do with quitting.</p>
 */
public final class OwnedPlayers {

    private final ShardsClient client;
    private final Logger logger;
    private final String serverName;
    private final Set<UUID> owned = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> lastWrittenAt = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> inFlight = ConcurrentHashMap.newKeySet();

    public OwnedPlayers(final ShardsClient client, final Logger logger, final String serverName) {
        this.client = client;
        this.logger = logger;
        this.serverName = serverName;
    }

    public void add(final UUID playerUuid) {
        this.owned.add(playerUuid);
        // Counts as just written: they arrived with what the store already has, so there is nothing to
        // checkpoint until they have played for a while
        this.lastWrittenAt.put(playerUuid, System.nanoTime());
    }

    /**
     * Stops treating a player as owned here without telling the proxy anything. For a transfer, where
     * the proxy gives the ownership up itself as part of moving them - releasing again from here
     * would be releasing a lock the destination has since taken.
     */
    public void forget(final UUID playerUuid) {
        this.owned.remove(playerUuid);
        this.lastWrittenAt.remove(playerUuid);
    }

    public boolean holds(final UUID playerUuid) {
        return this.owned.contains(playerUuid);
    }

    /**
     * Writes a player's state back and gives up ownership. Does nothing if this server does not hold
     * them - a player handed to another shard was released deliberately, and saving again here would
     * be writing over whatever they are doing now.
     *
     * <p>Must run on the main thread: the snapshot is read from a live player.</p>
     */
    public void saveAndRelease(final Player player) {
        final UUID playerUuid = player.getUniqueId();
        if (!this.owned.remove(playerUuid)) {
            return;
        }
        this.lastWrittenAt.remove(playerUuid);
        final PlayerSnapshot snapshot;
        final PlayerLocation location;
        try {
            snapshot = PlayerSnapshots.capture(player);
            location = toPlayerLocation(player.getLocation());
        } catch (final RuntimeException exception) {
            // Nothing is sent, so the lock stays held and the stored payload stays as it was. That is
            // recoverable by hand; sending a half-built snapshot would not be
            this.logger.log(Level.SEVERE, "Could not capture " + playerUuid + "; leaving their data owned here",
                exception);
            this.owned.add(playerUuid);
            return;
        }

        final String payload = Base64.getEncoder().encodeToString(PlayerSnapshotCodec.toBytes(snapshot));
        // Published from this thread rather than a scheduled task: quits also arrive during shutdown,
        // when the async scheduler is already gone. Only the reply is waited for asynchronously
        track(this.client.save(PlayerSaveRequest.create(this.serverName, playerUuid, payload, location))
            .whenComplete((response, error) -> logSaveResult(playerUuid, response, error)));
    }

    /**
     * Gives up ownership without writing anything back, for a login that was allowed and then never
     * completed. There is no player to read state from, and sending an empty payload would destroy
     * the data the lock exists to protect.
     */
    public void releaseWithoutSaving(final UUID playerUuid) {
        if (!this.owned.remove(playerUuid)) {
            return;
        }
        track(this.client.release(PlayerReleaseRequest.create(this.serverName, playerUuid))
            .whenComplete((response, error) -> {
                if (error != null) {
                    this.logger.log(Level.SEVERE, "Could not release the abandoned login of " + playerUuid, error);
                } else if (response.released()) {
                    this.logger.info("Released the lock on " + playerUuid + " after their login never completed");
                }
            }));
    }

    /**
     * Writes back the players who are due, keeping ownership of them.
     *
     * <p>Data is otherwise only written when someone leaves or crosses a border, so a server that is
     * killed rather than stopped loses everything since they arrived - they wake up wherever they last
     * crossed, with whatever they had then. Minecraft autosaves players for the same reason.</p>
     *
     * <p>Called every second and bounded, rather than writing everyone on one tick every interval.
     * Reading a player is tens of milliseconds, so a full server doing it all at once would be a
     * visible stall on a fixed cycle - the worst kind to diagnose.</p>
     *
     * <p>Must run on the main thread: snapshots are read from live players.</p>
     *
     * @param dueAfterNanos how old a write has to be before it is redone
     * @param maxPerRun the most players to write in one call
     */
    public void checkpointDue(final long dueAfterNanos, final int maxPerRun) {
        final long now = System.nanoTime();
        int written = 0;
        for (final UUID playerUuid : this.owned) {
            if (written >= maxPerRun) {
                return;
            }
            final Long last = this.lastWrittenAt.get(playerUuid);
            if (last != null && now - last < dueAfterNanos) {
                continue;
            }
            final Player player = Bukkit.getPlayer(playerUuid);
            if (player == null) {
                // Owned but not here: a login that has not finished, or one that never will. The
                // no-show timer deals with it; there is nothing to read from in the meantime
                continue;
            }
            checkpoint(player);
            written++;
        }
    }

    private void checkpoint(final Player player) {
        final UUID playerUuid = player.getUniqueId();
        // Marked before the write, not after: a write that fails should not have every following run
        // retry it immediately, and the next one is only a interval away
        this.lastWrittenAt.put(playerUuid, System.nanoTime());
        final String payload;
        final PlayerLocation location;
        try {
            payload = Base64.getEncoder().encodeToString(
                PlayerSnapshotCodec.toBytes(PlayerSnapshots.capture(player)));
            location = toPlayerLocation(player.getLocation());
        } catch (final RuntimeException exception) {
            this.logger.log(Level.SEVERE, "Could not capture " + playerUuid + " for a checkpoint", exception);
            return;
        }
        track(this.client.checkpoint(PlayerCheckpointRequest.create(this.serverName, playerUuid, payload, location))
            .whenComplete((response, error) -> {
                if (error != null) {
                    this.logger.log(Level.SEVERE, "Could not checkpoint " + playerUuid, error);
                } else if (response.status() != SaveStatus.SAVED) {
                    this.logger.severe("Checkpoint of " + playerUuid + " was refused: " + response.status()
                        + (response.heldBy() == null ? "" : " (held by " + response.heldBy() + ")"));
                }
            }));
    }

    /**
     * Waits for the saves already sent to be acknowledged.
     *
     * <p>Called during shutdown, after every player has been kicked. Returning before the proxy has
     * answered would leave their data owned by a server that no longer exists, so the next login
     * would be refused until something cleared the lock.</p>
     *
     * @return whether everything completed within the timeout
     */
    public boolean drain(final long timeout, final TimeUnit unit) {
        if (this.inFlight.isEmpty()) {
            return true;
        }
        this.logger.info("Waiting for " + this.inFlight.size() + " player data save(s) to complete");
        final CompletableFuture<?> all = CompletableFuture.allOf(this.inFlight.toArray(CompletableFuture[]::new));
        try {
            all.get(timeout, unit);
            return true;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final Exception exception) {
            this.logger.log(Level.SEVERE, "Not every player data save completed before shutdown", exception);
            return false;
        }
    }

    private void track(final CompletableFuture<?> future) {
        this.inFlight.add(future);
        future.whenComplete((result, error) -> this.inFlight.remove(future));
    }

    private void logSaveResult(final UUID playerUuid, final PlayerSaveResponse response, final Throwable error) {
        if (error != null) {
            this.logger.log(Level.SEVERE, "Could not save " + playerUuid + "; their lock is still held here", error);
            return;
        }
        if (response.status() != SaveStatus.SAVED) {
            // Every one of these means the state just read off a real player went nowhere
            this.logger.severe("Save of " + playerUuid + " was refused: " + response.status()
                + (response.heldBy() == null ? "" : " (held by " + response.heldBy() + ")"));
        }
    }

    private static PlayerLocation toPlayerLocation(final Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new PlayerLocation(location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
    }
}
