package net.civmc.shards.velocity.rabbitmq;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.civmc.shards.api.BorderProbeRequest;
import net.civmc.shards.api.BorderProbeResponse;
import net.civmc.shards.api.BorderProbeStatus;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.placement.ShardPlacementService;
import org.slf4j.Logger;

/**
 * Says what owns a place, so a shard can tell a door from a wall before anybody walks into it.
 *
 * <p>A shard learns only its own areas at startup - which is what keeps the shard map authoritative
 * in one process - so it knows where it ends but not what it ends against. This answers that without
 * moving anyone: nothing is written, no lock is taken, and a probe is never a partial transfer.</p>
 *
 * <p>It deliberately answers from the same {@link ShardPlacementService} that resolves a real
 * crossing, so what a player is shown at a border and what happens when they step over it cannot
 * disagree.</p>
 */
public final class BorderProbeHandler implements RequestHandler<BorderProbeRequest, BorderProbeResponse> {

    // The same budget a real transfer uses, so a shard that looks reachable here is one that would
    // have been accepted there
    private static final long REACHABILITY_TIMEOUT_MILLIS = 500L;
    // Probes arrive while players walk about near an edge, which is far more often than a shard's
    // health changes. Without this, a crowd standing at a border would ping the neighbour continuously
    private static final long REACHABILITY_CACHE_NANOS = TimeUnit.SECONDS.toNanos(2L);

    private final ShardPlacementService placementService;
    private final ProxyServer proxyServer;
    private final Logger logger;
    private final Map<String, Reachability> recentlyChecked = new ConcurrentHashMap<>();

    public BorderProbeHandler(final ShardPlacementService placementService, final ProxyServer proxyServer,
                              final Logger logger) {
        this.placementService = placementService;
        this.proxyServer = proxyServer;
        this.logger = logger;
    }

    @Override
    public String queue() {
        return ShardsRabbitMqTopology.BORDER_PROBE_QUEUE;
    }

    @Override
    public boolean durable() {
        // A probe is about where somebody is standing right now. One that survived a broker restart
        // would be answered long after it stopped being a question anyone had
        return ShardsRabbitMqTopology.BORDER_PROBE_QUEUE_DURABLE;
    }

    @Override
    public Class<BorderProbeRequest> requestType() {
        return BorderProbeRequest.class;
    }

    @Override
    public UUID requestId(final BorderProbeRequest request) {
        return request.requestId();
    }

    @Override
    public BorderProbeResponse handle(final BorderProbeRequest request) {
        final Optional<String> owner = this.placementService.shardFor(request.location());
        if (owner.isEmpty()) {
            // Shards are allowed not to touch, so ground owned by nobody is the configuration working
            // rather than a fault. It is a wall, and a permanent one
            return BorderProbeResponse.of(request.requestId(), BorderProbeStatus.UNOWNED, null);
        }
        final Optional<RegisteredServer> target = this.proxyServer.getServer(owner.get());
        if (target.isEmpty()) {
            this.logger.warn("Shard {} owns ground next to {} but is not registered with the proxy",
                owner.get(), request.serverName());
            return BorderProbeResponse.of(request.requestId(), BorderProbeStatus.UNREACHABLE, owner.get());
        }
        return BorderProbeResponse.of(request.requestId(),
            isReachable(target.get(), owner.get()) ? BorderProbeStatus.CROSSABLE : BorderProbeStatus.UNREACHABLE,
            owner.get());
    }

    private boolean isReachable(final RegisteredServer target, final String shardName) {
        final Reachability cached = this.recentlyChecked.get(shardName);
        if (cached != null && System.nanoTime() - cached.checkedAtNanos() < REACHABILITY_CACHE_NANOS) {
            return cached.reachable();
        }
        final boolean reachable = ping(target);
        this.recentlyChecked.put(shardName, new Reachability(reachable, System.nanoTime()));
        return reachable;
    }

    private boolean ping(final RegisteredServer target) {
        try {
            target.ping().get(REACHABILITY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final ExecutionException | TimeoutException exception) {
            return false;
        }
    }

    @Override
    public BorderProbeResponse failure(final UUID requestId, final String message) {
        return BorderProbeResponse.error(requestId, message);
    }

    private record Reachability(boolean reachable, long checkedAtNanos) {
    }
}
