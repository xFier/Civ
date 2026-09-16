package net.civmc.shards.velocity.rabbitmq;

import java.util.Base64;
import java.util.UUID;
import net.civmc.shards.api.PlayerCheckpointRequest;
import net.civmc.shards.api.PlayerCheckpointResponse;
import net.civmc.shards.api.SaveStatus;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import net.civmc.shards.velocity.playerdata.SaveResult;
import org.slf4j.Logger;

/**
 * Writes back a player who is still playing, without giving up the ownership the server holds.
 */
public final class PlayerCheckpointHandler
    implements RequestHandler<PlayerCheckpointRequest, PlayerCheckpointResponse> {

    private final PlayerDataService playerDataService;
    private final Logger logger;

    public PlayerCheckpointHandler(final PlayerDataService playerDataService, final Logger logger) {
        this.playerDataService = playerDataService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_CHECKPOINT_QUEUE;
    }

    @Override
    public Class<PlayerCheckpointRequest> requestType() {
        return PlayerCheckpointRequest.class;
    }

    @Override
    public UUID requestId(final PlayerCheckpointRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerCheckpointResponse handle(final PlayerCheckpointRequest request) {
        final SaveResult result = this.playerDataService.checkpoint(
            request.playerUuid(),
            ShardServerId.of(request.serverName()),
            Base64.getDecoder().decode(request.payload()),
            request.location());
        return switch (result) {
            case SaveResult.Success ignored ->
                PlayerCheckpointResponse.of(request.requestId(), SaveStatus.SAVED, null);
            case SaveResult.NotHeld ignored -> {
                // The server sending this believes it is serving the player, so nobody holding the
                // lock means its own claim has gone - quietly, and while they are still playing
                this.logger.error("Checkpoint of {} from {} matched nothing: the lock is not held",
                    request.playerUuid(), request.serverName());
                yield PlayerCheckpointResponse.of(request.requestId(), SaveStatus.NOT_HELD, null);
            }
            case SaveResult.NoRow ignored ->
                PlayerCheckpointResponse.of(request.requestId(), SaveStatus.NO_ROW, null);
            case SaveResult.HeldByOther heldByOther -> {
                // Two servers believe they are serving the same player right now. Nothing was written,
                // which is the lock doing its job, and this is as loud as it gets
                this.logger.error("Checkpoint of {} from {} refused: held by {}", request.playerUuid(),
                    request.serverName(), heldByOther.server());
                yield PlayerCheckpointResponse.of(request.requestId(), SaveStatus.HELD_BY_OTHER,
                    heldByOther.server());
            }
        };
    }

    @Override
    public PlayerCheckpointResponse failure(final UUID requestId, final String message) {
        return PlayerCheckpointResponse.error(requestId, message);
    }
}
