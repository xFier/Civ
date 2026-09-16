package net.civmc.shards.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Optional;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.placement.ShardConnectionListener;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.PlayerDataService;

@Plugin(id = "shards", name = "Shards", version = "1.0.0", authors = {"Fier"})
public final class ShardsVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Path dataDirectory;
    private final Injector injector;
    private ShardPlacementService shardPlacementService;
    private PlayerDataService playerDataService;

    @Inject
    public ShardsVelocityPlugin(final ProxyServer proxyServer, @DataDirectory final Path dataDirectory,
                                final Injector injector) {
        this.proxyServer = proxyServer;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        // Child of Velocity's injector for this plugin, which already provides ProxyServer, PluginContainer, Logger
        final Injector shardsInjector =
            this.injector.createChildInjector(new ShardsModule(ShardsConfig.load(this.dataDirectory)));

        this.proxyServer.getEventManager().register(this, shardsInjector.getInstance(ShardConnectionListener.class));

        this.shardPlacementService = shardsInjector.getInstance(ShardPlacementService.class);
        this.playerDataService = shardsInjector.getInstance(PlayerDataService.class);
    }

    /**
     * Shard lookups for other plugins. Empty until this plugin has handled ProxyInitializeEvent.
     */
    public Optional<ShardPlacementService> getPlacement() {
        return Optional.ofNullable(this.shardPlacementService);
    }

    /**
     * Single-owner player data access for other plugins. Empty until this plugin has handled
     * ProxyInitializeEvent.
     */
    public Optional<PlayerDataService> getPlayerData() {
        return Optional.ofNullable(this.playerDataService);
    }
}
