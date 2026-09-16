package net.civmc.shards.velocity.rabbitmq;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
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
        final Optional<String> destination = resolveDestination(request);
        if (destination.isEmpty()) {
            // Not an error for a location: shards are allowed not to touch, so ground owned by nobody
            // is a wall and the player simply stays where they are
            return PlayerTransferResponse.of(request.requestId(), TransferStatus.NO_DESTINATION,
                request.targetShard() == null
                    ? "No shard owns that location"
                    : "No shard named " + request.targetShard());
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
        // pre-login and a lock still held there refuses the login outright.
        // A shard-addressed transfer stores no location, so the destination places them with its own
        // spawn logic rather than at a coordinate the sender had no way to choose
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

        // Started, not waited for. The destination claims the player during its own pre-login, which
        // is a request this same consumer has to answer - so blocking here until the connect finishes
        // is waiting for a message that cannot be delivered until we stop waiting
        beginConnect(request, player.get(), target.get(), destination.get());
        return PlayerTransferResponse.transferred(request.requestId(), destination.get());
    }

    private Optional<String> resolveDestination(final PlayerTransferRequest request) {
        if (request.targetShard() != null) {
            // Checked against the shard map rather than taken on trust, so a server cannot send
            // someone to a name the proxy has never heard of
            return this.placementService.isShard(request.targetShard())
                ? Optional.of(request.targetShard())
                : Optional.empty();
        }
        return this.placementService.shardFor(request.targetLocation());
    }

    /**
     * Moves the player, without waiting to see whether it worked.
     *
     * <p>By this point their data is written and the lock released, so the answer to the sender is
     * already decided: the handover happened. What is left is only where the player's screen ends up,
     * and if that fails they are disconnected rather than left on a server that no longer owns them.
     * Their stored location is the destination, so reconnecting puts them where they were going.</p>
     */
    private void beginConnect(final PlayerTransferRequest request, final Player player,
                              final RegisteredServer target, final String destination) {
        player.createConnectionRequest(target).connect().whenComplete((result, error) -> {
            if (error == null && result != null && result.isSuccessful()) {
                return;
            }
            if (error == null) {
                this.logger.error("Transfer of {} to {} was refused by the destination", request.playerUuid(),
                    destination);
            } else {
                this.logger.error("Could not connect {} to {}", request.playerUuid(), destination, error);
            }
            player.disconnect(Component.text(
                "Could not reach " + destination + ". Please reconnect - your data is safe."));
        });
    }

    @Override
    public PlayerTransferResponse failure(final UUID requestId, final String message) {
        return PlayerTransferResponse.error(requestId, message);
    }
}
