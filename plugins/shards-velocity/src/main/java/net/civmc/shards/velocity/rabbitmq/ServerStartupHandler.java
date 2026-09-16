package net.civmc.shards.velocity.rabbitmq;

import java.util.UUID;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import org.slf4j.Logger;

/**
 * Answers a server that has just started by releasing the locks the run before it left behind.
 */
public final class ServerStartupHandler implements RequestHandler<ServerStartupRequest, ServerStartupResponse> {

    private final PlayerDataService playerDataService;
    private final Logger logger;

    public ServerStartupHandler(final PlayerDataService playerDataService, final Logger logger) {
        this.playerDataService = playerDataService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.SERVER_STARTUP_QUEUE;
    }

    @Override
    public Class<ServerStartupRequest> requestType() {
        return ServerStartupRequest.class;
    }

    @Override
    public UUID requestId(final ServerStartupRequest request) {
        return request.requestId();
    }

    @Override
    public ServerStartupResponse handle(final ServerStartupRequest request) {
        final int released = this.playerDataService.releaseAllForServer(ShardServerId.of(request.serverName()));
        this.logger.info("Released {} stale locks for server {}", released, request.serverName());
        return ServerStartupResponse.success(request.requestId(), released);
    }

    @Override
    public ServerStartupResponse failure(final UUID requestId, final String message) {
        return ServerStartupResponse.failure(requestId, message);
    }
}
