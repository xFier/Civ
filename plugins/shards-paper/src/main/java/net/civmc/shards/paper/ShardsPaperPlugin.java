package net.civmc.shards.paper;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.paper.border.ArrivalCue;
import net.civmc.shards.paper.border.BorderNotices;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.border.ShardBorderListener;
import net.civmc.shards.paper.border.TransferService;
import net.civmc.shards.paper.config.ShardsPaperConfig;
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.playerdata.PlayerDataListener;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.snapshot.SnapshotVerifyCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShardsPaperPlugin extends JavaPlugin {

    private static final long SHUTDOWN_DRAIN_SECONDS = 20L;
    private static final long SAVE_CHECK_TICKS = 20L;
    private static final int MAX_SAVES_PER_RUN = 4;
    private static final long STARTUP_RETRY_MIN_TICKS = 20L * 5L;
    private static final long STARTUP_RETRY_MAX_TICKS = 20L * 60L;

    private ShardsPaperConfig config;
    private ShardsClient client;
    private OwnedPlayers owned;
    private TransferService transfers;
    private final ShardBorder border = new ShardBorder();
    // Read from the login thread, written from whichever thread the startup answer arrives on
    private volatile boolean startupComplete;
    private long startupRetryTicks = STARTUP_RETRY_MIN_TICKS;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            this.config = ShardsPaperConfig.from(getConfig());
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Could not load config.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.client = new ShardsClient(this.config.connectionFactory(), this.config.serverName(), this, getLogger(),
            this::completeStartupHandshake);
        this.client.start();
        this.owned = new OwnedPlayers(this.client, getLogger(), this.config.serverName());

        final BorderNotices notices = new BorderNotices();
        this.transfers = new TransferService(this, this.client, this.owned, getLogger(),
            this.config.serverName(), this.config.failureMessage(), notices);

        final ArrivalCue arrivalCue = new ArrivalCue(this.config.arrivalTitle(), this.config.arrivalSubtitle());
        getServer().getPluginManager().registerEvents(
            new PlayerDataListener(this, this.client, this.config.serverName(), this.config.failureMessage(),
                this.owned, this.transfers, () -> this.startupComplete, arrivalCue), this);
        if (!arrivalCue.isConfigured()) {
            getLogger().info("No arrival title configured, so a crossing into this shard is unannounced");
        }
        getServer().getPluginManager().registerEvents(
            new ShardBorderListener(this.border, this.transfers, notices), this);
        getCommand("shardsnapshot").setExecutor(new SnapshotVerifyCommand());
        startPeriodicSave();
    }

    @Override
    public void onDisable() {
        // Kick first, so every quit handler runs and every save is sent while the plugin is still able
        // to send it. A player still online when the client closes has their data left owned by a
        // server that is about to stop existing
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.kick(Component.text("Server is shutting down"));
        }
        if (this.owned != null) {
            this.owned.drain(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS);
        }
        if (this.client != null) {
            this.client.close();
        }
    }

    /**
     * Writes back the players who are due, once a second.
     *
     * <p>Every second rather than once an interval, writing only those actually due and a few at a
     * time. Reading a player costs tens of milliseconds, so writing everyone on one tick would be a
     * stall that arrives on a fixed cycle - which looks like the server hitching for no reason.</p>
     */
    private void startPeriodicSave() {
        if (this.config.saveIntervalSeconds() <= 0) {
            getLogger().warning("Periodic saving is off: anything since a player arrived is lost if this "
                + "server is killed rather than stopped");
            return;
        }
        final long dueAfterNanos = TimeUnit.SECONDS.toNanos(this.config.saveIntervalSeconds());
        getServer().getScheduler().runTaskTimer(this, () -> this.owned.checkpointDue(dueAfterNanos, MAX_SAVES_PER_RUN),
            SAVE_CHECK_TICKS, SAVE_CHECK_TICKS);
        getLogger().info("Writing players back every " + this.config.saveIntervalSeconds() + "s");
    }

    /**
     * Moving a player to another shard, for other plugins.
     *
     * <p>Every reason for leaving a shard goes through the same thing - walking over a border, a
     * rocket landing, an arrival - so that writing a player back, giving up ownership and moving them
     * has one implementation rather than one per reason.</p>
     *
     * <p>State travels exactly as it is. A caller that wants a player to arrive without something has
     * to take it off them first; that is a rule of whatever is moving them, not of the transfer.</p>
     *
     * @return empty until this plugin has enabled
     */
    public Optional<TransferService> getTransfers() {
        return Optional.ofNullable(this.transfers);
    }

    /**
     * Asks the proxy to drop whatever locks this server still holds, and to say which areas it owns.
     *
     * <p>Releasing is correct only while nobody is online: anything held under this server's name then
     * was left by the run before it, whereas the same call with players on would drop the locks of the
     * people currently being served. That is why this runs on the first connection after enable - and
     * why the retry below is safe, because a login is refused until this has succeeded.</p>
     */
    private void completeStartupHandshake() {
        this.client.startup(ServerStartupRequest.create(this.config.serverName()))
            .whenComplete(this::logStartupResult);
    }

    private void logStartupResult(final ServerStartupResponse response, final Throwable error) {
        if (error != null) {
            getLogger().log(Level.SEVERE, "Could not complete the startup handshake", error);
            retryStartupHandshake();
            return;
        }
        if (!response.success()) {
            getLogger().severe("Could not complete the startup handshake: " + response.failureMessage());
            retryStartupHandshake();
            return;
        }
        this.startupComplete = true;
        getLogger().info("Released " + response.releasedLockCount() + " stale player data locks for "
            + this.config.serverName());

        // The proxy is the only holder of the shard map, so the areas this server owns arrive with the
        // startup answer rather than being configured a second time here
        this.border.set(response.regions());
        if (response.regions().isEmpty()) {
            getLogger().info("This server owns no shard areas, so no border is enforced");
        } else {
            getLogger().info("Enforcing " + response.regions().size() + " shard area(s)");
        }
    }

    /**
     * Asks again, rather than giving up for the lifetime of the server.
     *
     * <p>This used to be a log line and nothing else, which was the worse half of the failure: the
     * areas this server owns arrive with the same answer, so a handshake that never completed left
     * {@link ShardBorder} empty - and an empty border owns everywhere, so the server carried on
     * serving players with no edge enforced and nothing saying so. Refusing logins until this
     * succeeds is what makes both the wait and the repeated release safe.</p>
     */
    private void retryStartupHandshake() {
        getLogger().warning("This server has no shard areas yet, so nobody may join it. Trying again in "
            + (this.startupRetryTicks / 20L) + "s");
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::completeStartupHandshake,
            this.startupRetryTicks);
        this.startupRetryTicks = Math.min(this.startupRetryTicks * 2L, STARTUP_RETRY_MAX_TICKS);
    }
}
