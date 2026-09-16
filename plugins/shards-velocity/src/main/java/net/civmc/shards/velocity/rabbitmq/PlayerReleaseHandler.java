package net.civmc.shards.velocity.rabbitmq;

import java.util.UUID;
import net.civmc.shards.api.PlayerReleaseRequest;
import net.civmc.shards.api.PlayerReleaseResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import org.slf4j.Logger;

/**
 * Gives up ownership without writing anything back, for a login that was allowed and then abandoned.
 */
public final class PlayerReleaseHandler implements RequestHandler<PlayerReleaseRequest, PlayerReleaseResponse> {

    private final PlayerDataService playerDataService;
    private final Logger logger;

    public PlayerReleaseHandler(final PlayerDataService playerDataService, final Logger logger) {
        this.playerDataService = playerDataService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_RELEASE_QUEUE;
    }

    @Override
    public Class<PlayerReleaseRequest> requestType() {
        return PlayerReleaseRequest.class;
    }

    @Override
    public UUID requestId(final PlayerReleaseRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerReleaseResponse handle(final PlayerReleaseRequest request) {
        final boolean released = this.playerDataService.release(request.playerUuid(),
            ShardServerId.of(request.serverName()));
        if (released) {
            this.logger.info("Released the lock on {} held by {} for a login that never completed",
                request.playerUuid(), request.serverName());
        }
        return PlayerReleaseResponse.success(request.requestId(), released);
    }

    @Override
    public PlayerReleaseResponse failure(final UUID requestId, final String message) {
        return PlayerReleaseResponse.failure(requestId, message);
    }
}
