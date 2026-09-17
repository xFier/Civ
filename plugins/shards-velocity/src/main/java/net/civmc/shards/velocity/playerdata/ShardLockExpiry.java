package net.civmc.shards.velocity.playerdata;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.civmc.shards.api.ShardServerId;
import org.slf4j.Logger;

/**
 * Lets go of the players a shard was holding when it died.
 *
 * <p>A lock is only ever cleared by the server that holds it: it releases when a player quits, when
 * they are handed on, and it drops everything it still holds when it next starts. A shard that stops
 * and does not come back therefore keeps its players forever, and there is nothing they can do about
 * it - every login is refused as held by a server that is not there to let go.</p>
 *
 * <p>An expiry rather than a goodbye message from the shard, because the case that matters is the one
 * where the shard did not get to say goodbye. A clean stop already releases everything through the
 * saves its quit handling sends; what is left over is a crash, a kill, or a host that vanished.</p>
 *
 * <p>Nothing is discarded by this. The stored payload stays whatever was last written back, so what a
 * player loses is what they did since their last periodic save - the same loss they already take when
 * a shard is killed, not a new one.</p>
 */
public final class ShardLockExpiry {

    // Short: a shard that is up answers a ping on a local network in single figures, and a slow answer
    // does not need to be waited out here - being late once costs nothing, because it takes a whole
    // expiry window of consecutive failures to act
    private static final long PING_TIMEOUT_MILLIS = 1_000L;
    private static final long CONTRADICTION_REPORT_INTERVAL_MILLIS = TimeUnit.MINUTES.toMillis(5L);

    private final ProxyServer proxyServer;
    private final PlayerDataService playerDataService;
    private final List<String> serverNames;
    private final long expiryMillis;
    private final Logger logger;

    // When each server was first seen not answering, cleared the moment it answers again
    private final Map<String, Long> unreachableSince = new ConcurrentHashMap<>();
    // Servers whose locks have already been dropped, so a shard that stays down is acted on once
    // rather than every cycle
    private final Set<String> expired = ConcurrentHashMap.newKeySet();
    // When each server last had the contradiction below reported. It is worth repeating - it is a
    // shard the proxy cannot agree with itself about - but not at the rate this runs
    private final Map<String, Long> contradictionReportedAt = new ConcurrentHashMap<>();

    public ShardLockExpiry(final ProxyServer proxyServer, final PlayerDataService playerDataService,
                           final List<String> serverNames, final long expiryMillis, final Logger logger) {
        this.proxyServer = proxyServer;
        this.playerDataService = playerDataService;
        this.serverNames = List.copyOf(serverNames);
        this.expiryMillis = expiryMillis;
        this.logger = logger;
    }

    public void check() {
        for (final String serverName : this.serverNames) {
            try {
                check(serverName);
            } catch (final RuntimeException exception) {
                // One server's failure must not stop the others being checked, and this runs on a
                // timer with nobody to report to
                this.logger.error("Could not check whether {} is still alive", serverName, exception);
            }
        }
    }

    private void check(final String serverName) {
        final Optional<RegisteredServer> server = this.proxyServer.getServer(serverName);
        if (server.isEmpty()) {
            // Configured as a shard but not registered with the proxy. Nothing can be pinged and
            // nothing can be concluded, so this says so rather than guessing the server is dead
            this.logger.warn("Shard {} is not registered with the proxy, so its locks cannot be expired",
                serverName);
            return;
        }
        if (isAnswering(server.get())) {
            if (this.unreachableSince.remove(serverName) != null) {
                this.logger.info("Shard {} is answering again", serverName);
            }
            // Cleared on the way back up, so a shard that dies a second time is expired a second time
            this.expired.remove(serverName);
            this.contradictionReportedAt.remove(serverName);
            return;
        }

        final long now = System.currentTimeMillis();
        final long since = this.unreachableSince.computeIfAbsent(serverName, ignored -> now);
        if (now - since < this.expiryMillis || this.expired.contains(serverName)) {
            return;
        }

        if (!server.get().getPlayersConnected().isEmpty()) {
            // The proxy still has people on it, so it is not gone - it is not answering pings while
            // serving them. Dropping its locks here is the one mistake this must never make: it would
            // hand a live player's data to a second writer. Left alone, and said so occasionally,
            // because it stays true until one half of the contradiction goes away
            reportContradiction(serverName, server.get(), now);
            return;
        }

        this.expired.add(serverName);
        final int released = this.playerDataService.releaseAllForServer(ShardServerId.of(serverName));
        this.logger.warn("Shard {} has not answered for {}s and has nobody on it. Released {} player data "
                + "lock(s) it was still holding; those players keep whatever was last written back",
            serverName, this.expiryMillis / 1000L, released);
    }

    /**
     * Says, at most every few minutes, that a shard is both unreachable and serving players.
     */
    private void reportContradiction(final String serverName, final RegisteredServer server, final long now) {
        final Long reportedAt = this.contradictionReportedAt.get(serverName);
        if (reportedAt != null && now - reportedAt < CONTRADICTION_REPORT_INTERVAL_MILLIS) {
            return;
        }
        this.contradictionReportedAt.put(serverName, now);
        this.logger.error("Shard {} has not answered a ping for {}s but still has {} player(s) connected, so its "
                + "locks are being left alone", serverName, (now - this.unreachableSince.get(serverName)) / 1000L,
            server.getPlayersConnected().size());
    }

    private boolean isAnswering(final RegisteredServer server) {
        try {
            server.ping().get(PING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            return true;
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (final ExecutionException | TimeoutException exception) {
            return false;
        }
    }
}
