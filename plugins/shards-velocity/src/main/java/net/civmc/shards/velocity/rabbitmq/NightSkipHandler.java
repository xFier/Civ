package net.civmc.shards.velocity.rabbitmq;

import java.util.UUID;
import net.civmc.shards.api.NightSkipRequest;
import net.civmc.shards.api.NightSkipResponse;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.sky.SkyService;
import org.slf4j.Logger;

/**
 * Moves the network on to morning because somebody slept.
 *
 * <p>No TTL on this queue, unlike the state queue beside it. A state request that arrives late is
 * answering a question about a moment that has passed and is worth dropping; a night skip that
 * arrives late still means people went to bed, and dropping it would leave them awake in the dark
 * with no way to ask again.</p>
 */
public final class NightSkipHandler implements RequestHandler<NightSkipRequest, NightSkipResponse> {

    private final SkyService skyService;
    private final Logger logger;

    public NightSkipHandler(final SkyService skyService, final Logger logger) {
        this.skyService = skyService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.NIGHT_SKIP_QUEUE;
    }

    @Override
    public Class<NightSkipRequest> requestType() {
        return NightSkipRequest.class;
    }

    @Override
    public UUID requestId(final NightSkipRequest request) {
        return request.requestId();
    }

    @Override
    public NightSkipResponse handle(final NightSkipRequest request) {
        final SkyService.SkipOutcome outcome = this.skyService.skipNight();
        if (outcome.skipped()) {
            this.logger.info("{} slept through the night; the network is now at tick {}",
                request.serverName(), outcome.sky().fullTime());
        }
        return NightSkipResponse.of(request.requestId(), outcome.sky(), outcome.skipped());
    }

    @Override
    public NightSkipResponse failure(final UUID requestId, final String message) {
        return NightSkipResponse.error(requestId, message);
    }
}
