package net.civmc.shards.velocity.route;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jdbi.v3.core.JdbiException;
import org.slf4j.Logger;

public final class RouteListener {

    private final ProxyServer server;
    private final Logger logger;
    private final ShardsConfig config;
    private final RouteService routes;

    @Inject
    public RouteListener(final ProxyServer server, final Logger logger, final ShardsConfig config,
                         final RouteService routes) {
        this.server = server;
        this.logger = logger;
        this.config = config;
        this.routes = routes;
    }

    @Subscribe
    public void onInitial(final PlayerChooseInitialServerEvent event) {
        if (!event.getInitialServer().isEmpty()) {
            return;
        }

        final String expectedServer;
        try {
            expectedServer = this.routes.getExpectedServer(event.getPlayer().getUniqueId());
        } catch (final JdbiException exception) {
            this.logger.error("Failed to look up rocket player route", exception);
            event.getPlayer().disconnect(Component.text( "An error occurred, please try again later", NamedTextColor.RED));
            return;
        }

        final Optional<RegisteredServer> expected = this.server.getServer(expectedServer);
        if (expected.isEmpty()) {
            this.server.getServer(this.config.defaultServer()).ifPresent(event::setInitialServer);
            return;
        }
        event.setInitialServer(expected.get());
    }

    @Subscribe(priority = -1)
    public void onServerPreConnect(final ServerPreConnectEvent event) {
        if (this.routes.canBypass(event.getPlayer())) {
            return;
        }

        final RegisteredServer target = event.getResult().getServer().orElse(event.getOriginalServer());
        final String targetName = target.getServerInfo().getName();
        if (!this.routes.isProtectedServer(targetName)) {
            return;
        }

        final String expectedServer;
        try {
            expectedServer = this.routes.getExpectedServer(event.getPlayer().getUniqueId());
        } catch (final JdbiException exception) {
            this.logger.error("Failed to look up rocket player route", exception);
            routeToHoldingOrDisconnect(event);
            return;
        }

        if (targetName.equals(expectedServer)) {
            return;
        }

        forceExpectedServer(event, expectedServer);
    }

    private void routeToHoldingOrDisconnect(final ServerPreConnectEvent event) {
        final String holdingServer = this.config.holdingServer();
        if (!holdingServer.isEmpty()) {
            final Optional<RegisteredServer> server = this.server.getServer(holdingServer);
            if (server.isPresent()) {
                event.setResult(ServerPreConnectEvent.ServerResult.allowed(server.get()));
                return;
            }
        }
        event.getPlayer().disconnect(Component.text(this.config.failureMessage(), NamedTextColor.RED));
    }

    private void forceExpectedServer(final ServerPreConnectEvent event, final String expectedServer) {
        final Optional<RegisteredServer> expected = this.server.getServer(expectedServer);
        if (expected.isEmpty()) {
            this.logger.error("Configured rocket route server {} does not exist", expectedServer);
            routeToHoldingOrDisconnect(event);
            return;
        }
        event.setResult(ServerPreConnectEvent.ServerResult.allowed(expected.get()));
    }
}
