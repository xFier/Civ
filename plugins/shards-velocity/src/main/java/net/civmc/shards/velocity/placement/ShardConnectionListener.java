package net.civmc.shards.velocity.placement;

import com.google.inject.Inject;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Optional;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jdbi.v3.core.JdbiException;
import org.slf4j.Logger;

/**
 * Places a player on the shard that owns where they last were.
 *
 * <p>Only the initial connection is placed. Every later connection is started by a plugin or a
 * command, which is how a player is moved between shards deliberately, so those are left alone.</p>
 */
public final class ShardConnectionListener {

    private final ProxyServer proxyServer;
    private final Logger logger;
    private final ShardsConfig shardsConfig;
    private final ShardPlacementService shardPlacementService;

    @Inject
    public ShardConnectionListener(final ProxyServer proxyServer, final Logger logger,
                                   final ShardsConfig shardsConfig,
                                   final ShardPlacementService shardPlacementService) {
        this.proxyServer = proxyServer;
        this.logger = logger;
        this.shardsConfig = shardsConfig;
        this.shardPlacementService = shardPlacementService;
    }

    @Subscribe
    public EventTask onChooseInitialServer(final PlayerChooseInitialServerEvent event) {
        if (event.getInitialServer().isPresent()) {
            return null;
        }
        // The placement lookup reads the database, so it must not run on the event thread
        return EventTask.async(() -> {
            final Optional<String> shardName;
            try {
                shardName = this.shardPlacementService.shardForPlayer(event.getPlayer().getUniqueId());
            } catch (final JdbiException exception) {
                this.logger.error("Failed to look up the shard for {}", event.getPlayer().getUniqueId(), exception);
                refuse(event.getPlayer());
                return;
            }

            // No stored location yet, or a location no shard owns: park them rather than guess
            final String targetServerName = shardName.orElseGet(this.shardsConfig::holdingServer);
            final Optional<RegisteredServer> targetServer = targetServerName.isEmpty()
                ? Optional.empty()
                : this.proxyServer.getServer(targetServerName);
            if (targetServer.isEmpty()) {
                // Deliberately no fallback to another shard: sending a player to the wrong shard is
                // worse than refusing the connection, because their data would be loaded there
                this.logger.error("Cannot place {}: server {} is not registered with the proxy",
                    event.getPlayer().getUniqueId(),
                    targetServerName.isEmpty() ? "<no holding server configured>" : targetServerName);
                refuse(event.getPlayer());
                return;
            }
            event.setInitialServer(targetServer.get());
        });
    }

    private void refuse(final Player player) {
        player.disconnect(Component.text(this.shardsConfig.failureMessage(), NamedTextColor.RED));
    }
}
