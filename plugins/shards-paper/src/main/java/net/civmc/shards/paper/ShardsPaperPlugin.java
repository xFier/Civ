package net.civmc.shards.paper;

import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.paper.config.ShardsPaperConfig;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShardsPaperPlugin extends JavaPlugin {

    private ShardsPaperConfig config;
    private ShardsClient client;

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

        this.client = new ShardsClient(this.config.connectionFactory(), this, getLogger(), this::releaseStaleLocks);
        this.client.start();
    }

    @Override
    public void onDisable() {
        if (this.client != null) {
            this.client.close();
        }
    }

    /**
     * Asks the proxy to drop whatever locks this server still holds. Correct only because it runs on
     * the first connection after enable, when nobody is online: anything held under this server's
     * name then was left by the run before it. On a later reconnect the same call would release the
     * locks of the players currently being served.
     */
    private void releaseStaleLocks() {
        this.client.send(ServerStartupRequest.create(this.config.serverName()))
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
    }
}
