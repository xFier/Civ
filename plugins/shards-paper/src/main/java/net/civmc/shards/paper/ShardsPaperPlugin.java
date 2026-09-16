package net.civmc.shards.paper;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
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

    private ShardsPaperConfig config;
    private ShardsClient client;
    private OwnedPlayers owned;
    private TransferService transfers;
    private final ShardBorder border = new ShardBorder();

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
            this::releaseStaleLocks);
        this.client.start();
        this.owned = new OwnedPlayers(this.client, getLogger(), this.config.serverName());

        this.transfers = new TransferService(this, this.client, this.owned, getLogger(),
            this.config.serverName(), this.config.failureMessage());

        getServer().getPluginManager().registerEvents(
            new PlayerDataListener(this, this.client, this.config.serverName(), this.config.failureMessage(),
                this.owned, this.transfers), this);
        getServer().getPluginManager().registerEvents(new ShardBorderListener(this.border, this.transfers), this);
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
     * Asks the proxy to drop whatever locks this server still holds. Correct only because it runs on
     * the first connection after enable, when nobody is online: anything held under this server's
     * name then was left by the run before it. On a later reconnect the same call would release the
     * locks of the players currently being served.
     */
    private void releaseStaleLocks() {
        this.client.startup(ServerStartupRequest.create(this.config.serverName()))
            .whenComplete(this::logStartupResult);
    }

    private void logStartupResult(final ServerStartupResponse response, final Throwable error) {
        if (error != null) {
            getLogger().log(Level.SEVERE, "Could not release stale player data locks", error);
            return;
        }
        if (!response.success()) {
            getLogger().severe("Could not release stale player data locks: " + response.failureMessage());
            return;
        }
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
}
