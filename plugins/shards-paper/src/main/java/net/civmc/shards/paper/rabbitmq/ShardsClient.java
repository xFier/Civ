package net.civmc.shards.paper.rabbitmq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sends requests to the proxy and matches its answers back to them.
 *
 * <p>Replies arrive on a queue exclusive to this connection, named by the broker and torn down with
 * it, so nothing has to be configured per server and a dead server leaves no queue behind.</p>
 */
public final class ShardsClient implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long RESPONSE_TIMEOUT_SECONDS = 15L;
    private static final long RECONNECT_DELAY_TICKS = 20L * 5L;
    // Dropped rather than delivered late: a reply nobody is still waiting for is of no use, and this
    // keeps an unreachable server from accumulating them
    private static final int REPLY_TTL_MILLIS = 20_000;

    private final ConnectionFactory connectionFactory;
    private final JavaPlugin plugin;
    private final Logger logger;
    private final Runnable onFirstConnect;
    private final AtomicBoolean firstConnectDone = new AtomicBoolean();
    private final Map<UUID, CompletableFuture<ServerStartupResponse>> pendingResponses = new ConcurrentHashMap<>();
    private volatile boolean closed;
    private volatile boolean ready;
    private Connection connection;
    private Channel channel;
    private String replyQueue;

    /**
     * @param onFirstConnect run once, after the first connection this client establishes. Reconnects
     *     deliberately do not run it: what it sends is only correct for a server with nobody online
     */
    public ShardsClient(final ConnectionFactory connectionFactory, final JavaPlugin plugin, final Logger logger,
                        final Runnable onFirstConnect) {
        this.connectionFactory = connectionFactory;
        this.plugin = plugin;
        this.logger = logger;
        this.onFirstConnect = onFirstConnect;
    }

    public boolean start() {
        return connect();
    }

    private synchronized boolean connect() {
        if (this.closed) {
            return false;
        }
        try {
            this.connection = this.connectionFactory.newConnection("shards-paper");
            this.channel = this.connection.createChannel();
            final Map<String, Object> arguments = new HashMap<>();
            arguments.put("x-message-ttl", REPLY_TTL_MILLIS);
            this.replyQueue = this.channel.queueDeclare("", false, true, true, arguments).getQueue();
            final DeliverCallback deliverCallback = (consumerTag, delivery) -> handleResponse(delivery.getBody());
            this.channel.basicConsume(this.replyQueue, true, deliverCallback, consumerTag -> {
            });
            this.ready = true;
        } catch (final ConnectException exception) {
            this.logger.warning("Retrying RabbitMQ connection");
            Bukkit.getScheduler().runTaskLaterAsynchronously(this.plugin, this::connect, RECONNECT_DELAY_TICKS);
            return false;
        } catch (final IOException | TimeoutException exception) {
            this.logger.log(Level.SEVERE, "Failed to connect to RabbitMQ", exception);
            closeQuietly();
            return false;
        }
        if (this.firstConnectDone.compareAndSet(false, true)) {
            this.onFirstConnect.run();
        }
        return true;
    }

    public CompletableFuture<ServerStartupResponse> send(final ServerStartupRequest request) {
        return publish(ShardsRabbitMqTopology.SERVER_STARTUP_QUEUE, request.requestId(), request);
    }

    private CompletableFuture<ServerStartupResponse> publish(final String queue, final UUID requestId,
                                                             final Object body) {
        final CompletableFuture<ServerStartupResponse> responseFuture = new CompletableFuture<>();
        if (!this.ready || this.channel == null || !this.channel.isOpen()) {
            responseFuture.completeExceptionally(new IllegalStateException("Not connected to RabbitMQ"));
            return responseFuture;
        }
        this.pendingResponses.put(requestId, responseFuture);
        responseFuture.orTimeout(RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete((response, error) -> this.pendingResponses.remove(requestId));
        try {
            final AMQP.BasicProperties properties = new AMQP.BasicProperties.Builder()
                .contentType(ShardsRabbitMqTopology.CONTENT_TYPE_JSON)
                .correlationId(requestId.toString())
                .replyTo(this.replyQueue)
                .deliveryMode(2)
                .build();
            synchronized (this) {
                this.channel.basicPublish("", queue, properties, GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
            }
        } catch (final IOException exception) {
            this.pendingResponses.remove(requestId);
            responseFuture.completeExceptionally(exception);
        }
        return responseFuture;
    }

    private void handleResponse(final byte[] body) {
        final ServerStartupResponse response;
        try {
            response = GSON.fromJson(new String(body, StandardCharsets.UTF_8), ServerStartupResponse.class);
        } catch (final JsonParseException exception) {
            this.logger.log(Level.WARNING, "Dropping malformed response", exception);
            return;
        }
        final CompletableFuture<ServerStartupResponse> responseFuture =
            this.pendingResponses.remove(response.requestId());
        if (responseFuture == null) {
            this.logger.log(Level.FINE, "Dropping unmatched response " + response.requestId());
            return;
        }
        responseFuture.complete(response);
    }

    @Override
    public void close() {
        this.closed = true;
        this.ready = false;
        this.pendingResponses.values()
            .forEach(pending -> pending.completeExceptionally(new IllegalStateException("Client closed")));
        this.pendingResponses.clear();
        closeQuietly();
    }

    private void closeQuietly() {
        if (this.channel != null) {
            try {
                this.channel.close();
            } catch (final IOException | TimeoutException exception) {
                this.logger.log(Level.WARNING, "Failed to close the RabbitMQ channel", exception);
            } finally {
                this.channel = null;
            }
        }
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (final IOException exception) {
                this.logger.log(Level.WARNING, "Failed to close the RabbitMQ connection", exception);
            } finally {
                this.connection = null;
            }
        }
    }
}
