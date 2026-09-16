package net.civmc.shards.velocity.rabbitmq;

import java.util.Base64;
import java.util.UUID;
import net.civmc.shards.api.PlayerSaveRequest;
import net.civmc.shards.api.PlayerSaveResponse;
import net.civmc.shards.api.SaveStatus;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import net.civmc.shards.velocity.playerdata.SaveResult;
import org.slf4j.Logger;

/**
 * Writes a player's state back and gives up the ownership the sending server held.
 */
public final class PlayerSaveHandler implements RequestHandler<PlayerSaveRequest, PlayerSaveResponse> {

    private final PlayerDataService playerDataService;
    private final Logger logger;

    public PlayerSaveHandler(final PlayerDataService playerDataService, final Logger logger) {
        this.playerDataService = playerDataService;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.PLAYER_SAVE_QUEUE;
    }

    @Override
    public Class<PlayerSaveRequest> requestType() {
        return PlayerSaveRequest.class;
    }

    @Override
    public UUID requestId(final PlayerSaveRequest request) {
        return request.requestId();
    }

    @Override
    public PlayerSaveResponse handle(final PlayerSaveRequest request) {
        final SaveResult result = this.playerDataService.saveAndRelease(
            request.playerUuid(),
            ShardServerId.of(request.serverName()),
            Base64.getDecoder().decode(request.payload()),
            request.location());
        return switch (result) {
            case SaveResult.Success ignored -> PlayerSaveResponse.of(request.requestId(), SaveStatus.SAVED, null);
            case SaveResult.NotHeld ignored -> {
                // The lock was already given up, which is what a transfer does deliberately. Not an
                // error here; the sender decides whether it expected to still hold it
                yield PlayerSaveResponse.of(request.requestId(), SaveStatus.NOT_HELD, null);
            }
            case SaveResult.NoRow ignored -> PlayerSaveResponse.of(request.requestId(), SaveStatus.NO_ROW, null);
            case SaveResult.HeldByOther heldByOther -> {
                // Two servers believe they own one player. Nothing was written, which is the lock
                // doing its job, but it should never happen and the data just went nowhere
                this.logger.error("Refused save of {} from {}: held by {}", request.playerUuid(),
                    request.serverName(), heldByOther.server());
                yield PlayerSaveResponse.of(request.requestId(), SaveStatus.HELD_BY_OTHER, heldByOther.server());
            }
        };
    }

    @Override
    public PlayerSaveResponse failure(final UUID requestId, final String message) {
        return PlayerSaveResponse.error(requestId, message);
    }
}
