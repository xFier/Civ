package net.civmc.shards.velocity.route;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.velocitypowered.api.proxy.Player;
import java.util.UUID;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.database.PlayerRouteStatements;

@Singleton
public final class RouteService {

    private final ShardsConfig config;
    private final PlayerRouteStatements routeStatements;

    @Inject
    public RouteService(final ShardsConfig config, final PlayerRouteStatements routeStatements) {
        this.config = config;
        this.routeStatements = routeStatements;
    }

    public boolean isProtectedServer(final String serverName) {
        return serverName.equals(this.config.mainServer()) || serverName.equals(this.config.zorwethServer());
    }

    public boolean canBypass(final Player player) {
        return player.hasPermission("zorweth.admin");
    }

    public String getExpectedServer(final UUID playerId) {
        return this.routeStatements.findExpectedServer(playerId).orElse(this.config.defaultServer());
    }

    public void setExpectedServer(final UUID playerId, final String expectedServer) {
        if (!isProtectedServer(expectedServer)) {
            throw new IllegalArgumentException("Expected server must be " + this.config.mainServer() + " or " + this.config.zorwethServer());
        }
        this.routeStatements.setExpectedServer(playerId, expectedServer);
    }
}
