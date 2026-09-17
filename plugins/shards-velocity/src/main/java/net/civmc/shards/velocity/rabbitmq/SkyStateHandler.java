package net.civmc.shards.velocity.rabbitmq;

import java.util.Map;
import java.util.UUID;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.api.SkyStateRequest;
import net.civmc.shards.api.SkyStateResponse;
import net.civmc.shards.velocity.sky.SkyService;

/**
 * Tells a shard what the sky should look like.
 *
 * <p>Reads nothing and writes nothing outside the proxy's own clock, so it is safe to ask as often
 * as a shard likes - which it does, every few seconds, for as long as it is up.</p>
 */
public final class SkyStateHandler implements RequestHandler<SkyStateRequest, SkyStateResponse> {

    private final SkyService skyService;

    public SkyStateHandler(final SkyService skyService) {
        this.skyService = skyService;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.SKY_STATE_QUEUE;
    }

    @Override
    public Map<String, Object> arguments() {
        return Map.of("x-message-ttl", ShardsRabbitMqTopology.SKY_STATE_TTL_MILLIS);
    }

    @Override
    public Class<SkyStateRequest> requestType() {
        return SkyStateRequest.class;
    }

    @Override
    public UUID requestId(final SkyStateRequest request) {
        return request.requestId();
    }

    @Override
    public SkyStateResponse handle(final SkyStateRequest request) {
        return SkyStateResponse.of(request.requestId(), this.skyService.current());
    }

    @Override
    public SkyStateResponse failure(final UUID requestId, final String message) {
        return SkyStateResponse.error(requestId, message);
    }
}
