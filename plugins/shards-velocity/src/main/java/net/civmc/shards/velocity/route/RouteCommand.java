package net.civmc.shards.velocity.route;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.jdbi.v3.core.JdbiException;
import org.slf4j.Logger;

public final class RouteCommand implements SimpleCommand {

    private final Logger logger;
    private final ShardsConfig config;
    private final RouteService routeService;
    private final PlayerIdResolver playerIdResolver;

    @Inject
    public RouteCommand(final Logger logger, final ShardsConfig config, final RouteService routeService,
                        final PlayerIdResolver playerIdResolver) {
        this.logger = logger;
        this.config = config;
        this.routeService = routeService;
        this.playerIdResolver = playerIdResolver;
    }

    @Override
    public void execute(final Invocation invocation) {
        final CommandSource source = invocation.source();
        if (!source.hasPermission("zorweth.route")) {
            source.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return;
        }
        final String[] args = invocation.arguments();
        if (args.length != 2) {
            source.sendMessage(Component.text("Usage: /shardroute <player|uuid> <main|zorweth>", NamedTextColor.RED));
            return;
        }

        final String expectedServer = args[1];
        if (!this.routeService.isProtectedServer(expectedServer)) {
            source.sendMessage(Component.text("Server must be " + this.config.mainServer() + " or " + this.config.zorwethServer(), NamedTextColor.RED));
            return;
        }

        CompletableFuture.runAsync(() -> {
            final UUID playerId = this.playerIdResolver.parsePlayerId(args[0]);
            if (playerId == null) {
                source.sendMessage(Component.text("Unknown player. Use a UUID for offline players not in NameAPI.", NamedTextColor.RED));
                return;
            }

            try {
                this.routeService.setExpectedServer(playerId, expectedServer);
            } catch (final JdbiException exception) {
                source.sendMessage(Component.text("Failed to update route.", NamedTextColor.RED));
                this.logger.error("Failed to override Zorweth route", exception);
                return;
            }
            source.sendMessage(Component.text("Set " + playerId + " expected server to " + expectedServer + ".", NamedTextColor.GREEN));
        });
    }

    @Override
    public boolean hasPermission(final Invocation invocation) {
        return invocation.source().hasPermission("zorweth.admin");
    }
}
