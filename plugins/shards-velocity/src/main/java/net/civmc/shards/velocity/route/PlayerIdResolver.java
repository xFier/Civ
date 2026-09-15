package net.civmc.shards.velocity.route;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

@Singleton
public final class PlayerIdResolver {

    private final ProxyServer proxyServer;
    private Function<String, UUID> offlinePlayerResolver = ignored -> null;

    @Inject
    public PlayerIdResolver(final ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    public void setOfflinePlayerResolver(final Function<String, UUID> offlinePlayerResolver) {
        this.offlinePlayerResolver = Objects.requireNonNull(offlinePlayerResolver);
    }

    public UUID parsePlayerId(final String input) {
        try {
            return UUID.fromString(input);
        } catch (final IllegalArgumentException ignored) {
            final Optional<Player> onlinePlayer = this.proxyServer.getPlayer(input);
            if (onlinePlayer.isPresent()) {
                return onlinePlayer.get().getUniqueId();
            }
            return this.offlinePlayerResolver.apply(input);
        }
    }
}
