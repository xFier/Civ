package net.civmc.shards.velocity.rabbitmq;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import net.civmc.shards.api.PlayerTransferRequest;
import net.civmc.shards.api.PlayerTransferResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.api.TransferStatus;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import net.civmc.shards.velocity.playerdata.SaveResult;
import org.slf4j.Logger;

/**
 * Moves a player to whichever shard owns the location they are heading for.
 *
 * <p>The proxy does both halves - writing the player back and connecting them - rather than replying
 * and letting the sending server connect them. It holds the lock and the player's connection, so
 * release-then-connect is one local sequence here. Split across two processes there would be a window
 * where the player is still online on the server they are leaving while owning nothing, and their
 * eventual quit would save into a lock they no longer hold.</p>
 */
public final class PlayerTransferHandler implements RequestHandler<PlayerTransferRequest, PlayerTransferResponse> {

    private final PlayerDataService playerDataService;
    private final ShardPlacementService placementService;
    private final ProxyServer proxyServer;
    private final Logger logger;

    public PlayerTransferHandler(final PlayerDataService playerDataService,
                                 final ShardPlacementService placementService, final ProxyServer proxyServer,
                                 final Logger logger) {
        this.playerDataService = playerDataService;
        this.placementService = placementService;
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_TRANSFER_QUEUE;
    }

    @Override
    public Class<PlayerTransferRequest> requestType() {
        return PlayerTransferRequest.class;
    }

    @Override
    public UUID requestId(final PlayerTransferRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerTransferResponse handle(final PlayerTransferRequest request) {
        final Optional<String> destination = this.placementService.shardFor(request.targetLocation());
        if (destination.isEmpty()) {
            // Not an error. Shards are allowed not to touch, so ground owned by nobody is a wall and
            // the player simply stays where they are
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.NO_DESTINATION,
                "No shard owns that location");
        }

        final Optional<RegisteredServer> target = this.proxyServer.getServer(destination.get());
        if (target.isEmpty()) {
            this.logger.error("Shard {} owns the target location but is not registered with the proxy",
                destination.get());
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.DESTINATION_UNAVAILABLE,
                "Destination shard is not registered");
        }

        final Optional<Player> player = this.proxyServer.getPlayer(request.playerUuid());
        if (player.isEmpty()) {
            // They disconnected while the transfer was in flight. Their data is still owned by the
            // server they were on, whose quit handling writes it back - so nothing is saved here
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.DESTINATION_UNAVAILABLE,
                "Player is no longer connected");
        }

        // Written back and released before the connect, because the destination claims during its own
        // pre-login and a lock still held there refuses the login outright
        final SaveResult saveResult = this.playerDataService.saveAndRelease(
            request.playerUuid(),
            ShardServerId.of(request.serverName()),
            Base64.getDecoder().decode(request.payload()),
            request.targetLocation());
        if (!(saveResult instanceof SaveResult.Success)) {
            // Nothing was written, so nothing was released either. Moving them now would hand the
            // destination a stale copy and strand the real one
            this.logger.error("Refusing to transfer {}: save was refused with {}", request.playerUuid(),
                saveResult.getClass().getSimpleName());
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.SAVE_REFUSED,
                "Could not write player data back");
        }

        return connect(request, player.get(), target.get(), destination.get());
    }

    private PlayerTransferResponse connect(final PlayerTransferRequest request, final Player player,
                                           final RegisteredServer target, final String destination) {
        try {
            final ConnectionRequestBuilder.Result result = player.createConnectionRequest(target).connect()
                .join();
            if (result.isSuccessful()) {
                return PlayerTransferResponse.transferred(request.requestId(), destination);
            }
            // The lock is already released and the payload already written, so their data is safe and
            // sitting at the destination. Reconnecting picks it up; staying here does not
            this.logger.error("Transfer of {} to {} was refused by the destination", request.playerUuid(),
                destination);
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.DESTINATION_UNAVAILABLE,
                "Destination refused the connection");
        } catch (final RuntimeException exception) {
            this.logger.error("Could not connect {} to {}", request.playerUuid(), destination, exception);
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.DESTINATION_UNAVAILABLE,
                "Could not connect to the destination");
        }
    }

    @Override
    public PlayerTransferResponse failure(final UUID requestId, final String message) {
        return PlayerTransferResponse.error(requestId, message);
    }
}
