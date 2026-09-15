package net.civmc.shards.velocity;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.route.PlayerIdResolver;
import net.civmc.shards.velocity.route.RouteCommand;
import net.civmc.shards.velocity.route.RouteListener;
import net.civmc.shards.velocity.route.RouteService;

@Plugin(id = "shards", name = "Shards", version = "1.0.0", authors = {"Fier"})
public final class ShardsVelocityPlugin {

    private final ProxyServer proxyServer;
    private final Path dataDirectory;
    private final Injector injector;
    private final PlayerIdResolver playerIdResolver;
    private RouteService routeService;

    @Inject
    public ShardsVelocityPlugin(final ProxyServer proxyServer, @DataDirectory final Path dataDirectory,
                                final Injector injector) {
        this.proxyServer = proxyServer;
        this.dataDirectory = dataDirectory;
        this.injector = injector;
        // Only needs bindings Velocity already provides, so it's available before ProxyInitializeEvent for other
        // plugins to set the offline resolver on. The child injector below reuses this same singleton.
        this.playerIdResolver = injector.getInstance(PlayerIdResolver.class);
    }

    @Subscribe
    public void onProxyInitialization(final ProxyInitializeEvent event) {
        // Child of Velocity's injector for this plugin, which already provides ProxyServer, PluginContainer, Logger
        final Injector shards = this.injector.createChildInjector(new ShardsModule(ShardsConfig.load(this.dataDirectory)));

        this.proxyServer.getEventManager().register(this, shards.getInstance(RouteListener.class));

        final CommandManager commandManager = this.proxyServer.getCommandManager();
        final CommandMeta routeMeta = commandManager.metaBuilder("shardroute")
            .plugin(this)
            .build();
        commandManager.register(routeMeta, shards.getInstance(RouteCommand.class));

        this.routeService = shards.getInstance(RouteService.class);
    }

    /**
     * Route checks for other plugins. Empty until this plugin has handled ProxyInitializeEvent.
     */
    public Optional<RouteService> getRoutes() {
        return Optional.ofNullable(this.routeService);
    }

    public void setOfflinePlayerResolver(final Function<String, UUID> offlinePlayerResolver) {
        this.playerIdResolver.setOfflinePlayerResolver(offlinePlayerResolver);
    }
}
