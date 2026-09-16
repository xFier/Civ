package net.civmc.shards.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.placement.ShardConnectionListener;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.InFlightTransfers;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import net.civmc.shards.velocity.rabbitmq.PlayerCheckpointHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerClaimHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerReleaseHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerSaveHandler;
import net.civmc.shards.velocity.rabbitmq.PlayerTransferHandler;
import net.civmc.shards.velocity.rabbitmq.ServerStartupHandler;
import net.civmc.shards.velocity.rabbitmq.ShardsRequestConsumer;
import org.slf4j.Logger;

@Plugin(id = "shards", name = "Shards", version = "1.0.0", authors = {"Fier"})
public final class ShardsVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Path dataDirectory;
    private final Injector injector;
    private ShardPlacementService shardPlacementService;
    private PlayerDataService playerDataService;
    private ShardsRequestConsumer requestConsumer;

    @Inject
    public ShardsVelocityPlugin(final ProxyServer proxyServer, final Logger logger,
                                @DataDirectory final Path dataDirectory, final Injector injector) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        final ShardsConfig shardsConfig = ShardsConfig.load(this.dataDirectory);
        logServerIds(shardsConfig);

        // Child of Velocity's injector for this plugin, which already provides ProxyServer, PluginContainer, Logger
        final Injector shardsInjector = this.injector.createChildInjector(new ShardsModule(shardsConfig));

        this.proxyServer.getEventManager().register(this, shardsInjector.getInstance(ShardConnectionListener.class));

        this.shardPlacementService = shardsInjector.getInstance(ShardPlacementService.class);
        this.playerDataService = shardsInjector.getInstance(PlayerDataService.class);

        // Shared by the two handlers that between them make a crossing tellable from a login: the
        // transfer writes the record and the claim that follows reads it
        final InFlightTransfers inFlightTransfers = new InFlightTransfers();
        this.requestConsumer = new ShardsRequestConsumer(shardsConfig.rabbitmq().connectionFactory(),
            List.of(
                new ServerStartupHandler(this.playerDataService, this.shardPlacementService, this.logger),
                new PlayerClaimHandler(this.playerDataService, inFlightTransfers, this.logger),
                new PlayerSaveHandler(this.playerDataService, this.logger),
                new PlayerCheckpointHandler(this.playerDataService, this.logger),
                new PlayerReleaseHandler(this.playerDataService, this.logger),
                new PlayerTransferHandler(this.playerDataService, this.shardPlacementService,
                    inFlightTransfers, this.proxyServer, this.logger)),
            this.proxyServer, this, this.logger);
        if (!this.requestConsumer.start()) {
            this.logger.warn("Shards could not start its request consumer; no server can reach its player data");
        }
    }

    @Subscribe
    public void onProxyShutdown(final ProxyShutdownEvent event) {
        // The database pool closes itself through the hook DatabaseModule registers when it opens it
        if (this.requestConsumer != null) {
            this.requestConsumer.close();
        }
    }

    /**
     * The id a server owns player data under is derived from its name rather than configured, so an
     * operator reading owning_server_uuid out of the database has nothing on hand to map it back to a
     * server. Writing the mapping out once at startup gives them that.
     */
    private void logServerIds(final ShardsConfig shardsConfig) {
        final Set<String> serverNames = new LinkedHashSet<>(shardsConfig.shards().keySet());
        // The holding server is not a shard, but it owns a player's data while they are on it
        if (!shardsConfig.holdingServer().isEmpty()) {
            serverNames.add(shardsConfig.holdingServer());
        }
        for (final String serverName : serverNames) {
            this.logger.info("Server {} owns player data as {}", serverName, ShardServerId.of(serverName));
        }
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
