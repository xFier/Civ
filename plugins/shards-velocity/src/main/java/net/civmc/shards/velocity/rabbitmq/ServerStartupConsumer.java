package net.civmc.shards.velocity.rabbitmq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.api.ShardServerId;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.velocity.playerdata.PlayerDataService;
import org.slf4j.Logger;

/**
 * Answers the message a server sends once it has started, by releasing the locks left behind by the
 * run before it.
 */
public final class ServerStartupConsumer implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long RECONNECT_DELAY_SECONDS = 5L;
    // Deliveries are handled concurrently rather than one at a time: the player data messages that
    // will share this consumer sit on the login path, where a prefetch of one would queue every login
    // on the network behind every other. PlayerDataService keeps no state between calls
    private static final int PREFETCH_COUNT = 16;

    private final ConnectionFactory connectionFactory;
    private final PlayerDataService playerDataService;
    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Logger logger;
    private Connection connection;
    private Channel channel;

    public ServerStartupConsumer(final ConnectionFactory connectionFactory,
                                 final PlayerDataService playerDataService, final ProxyServer proxyServer,
                                 final Object plugin, final Logger logger) {
        this.connectionFactory = connectionFactory;
        this.playerDataService = playerDataService;
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.logger = logger;
    }

    public boolean start() {
        return connect();
    }

    private synchronized boolean connect() {
        try {
            this.connection = this.connectionFactory.newConnection("shards-velocity-server-startup");
            this.channel = this.connection.createChannel();
            this.channel.queueDeclare(
                ShardsRabbitMqTopology.SERVER_STARTUP_QUEUE,
                ShardsRabbitMqTopology.SERVER_STARTUP_QUEUE_DURABLE,
                false,
                false,
                null);
            this.channel.basicQos(PREFETCH_COUNT);
            final DeliverCallback deliverCallback = (consumerTag, delivery) -> handleDelivery(
                delivery.getBody(), delivery.getProperties(), delivery.getEnvelope().getDeliveryTag());
            this.channel.basicConsume(ShardsRabbitMqTopology.SERVER_STARTUP_QUEUE, false, deliverCallback,
                consumerTag -> {
                });
            this.logger.info("Shards server startup consumer connected");
            return true;
        } catch (final ConnectException exception) {
            this.logger.warn("Retrying RabbitMQ connection for the Shards server startup consumer");
            this.proxyServer.getScheduler().buildTask(this.plugin, this::connect)
                .delay(RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
                .schedule();
            return false;
        } catch (final IOException | TimeoutException exception) {
            this.logger.error("Failed to connect the Shards server startup consumer", exception);
            return false;
        }
    }

    private void handleDelivery(final byte[] body, final AMQP.BasicProperties properties, final long deliveryTag)
        throws IOException {
        // Parsing and handling are separate because a body we cannot read and a release we could not
        // perform are different answers, and because anything that escaped here would leave the
        // delivery unacked to be redelivered forever
        ServerStartupRequest request = null;
        ServerStartupResponse response = null;
        try {
            request = GSON.fromJson(new String(body, StandardCharsets.UTF_8), ServerStartupRequest.class);
            if (request == null) {
                throw new JsonParseException("Empty body");
            }
        } catch (final RuntimeException exception) {
            // Caught as RuntimeException, not JsonParseException: the record's own validation runs
            // during deserialization, and Gson reports a constructor that rejected its arguments as a
            // plain RuntimeException. Answered rather than only logged, so the sender fails now
            // instead of waiting out its response timeout
            this.logger.warn("Rejecting malformed Shards server startup request", exception);
            response = failureResponse(null, properties, "Malformed server startup request");
        }

        if (request != null) {
            try {
                final int released = this.playerDataService.releaseAllForServer(ShardServerId.of(request.serverName()));
                this.logger.info("Released {} stale locks for server {}", released, request.serverName());
                response = ServerStartupResponse.success(request.requestId(), released);
            } catch (final RuntimeException exception) {
                this.logger.error("Failed to release stale locks for server {}", request.serverName(), exception);
                response = failureResponse(request, properties, "Could not release stale locks");
            }
        }

        if (response != null) {
            publishResponse(properties, response);
        }
        this.channel.basicAck(deliveryTag, false);
    }

    private ServerStartupResponse failureResponse(final ServerStartupRequest request,
                                                  final AMQP.BasicProperties properties, final String message) {
        final UUID requestId = request == null ? parseCorrelationId(properties) : request.requestId();
        if (requestId == null) {
            return null;
        }
        return ServerStartupResponse.failure(requestId, message);
    }

    private void publishResponse(final AMQP.BasicProperties requestProperties, final ServerStartupResponse response)
        throws IOException {
        if (requestProperties == null || requestProperties.getReplyTo() == null
            || requestProperties.getReplyTo().isBlank()) {
            this.logger.warn("Dropping Shards server startup response {} because no reply queue was provided",
                response.requestId());
            return;
        }
        final AMQP.BasicProperties responseProperties = new AMQP.BasicProperties.Builder()
            .contentType(ShardsRabbitMqTopology.CONTENT_TYPE_JSON)
            .correlationId(response.requestId().toString())
            .deliveryMode(1)
            .build();
        this.channel.basicPublish("", requestProperties.getReplyTo(), responseProperties,
            GSON.toJson(response).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID parseCorrelationId(final AMQP.BasicProperties properties) {
        if (properties == null || properties.getCorrelationId() == null || properties.getCorrelationId().isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(properties.getCorrelationId());
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    @Override
    public void close() {
        closeChannel();
        closeConnection();
    }

    private void closeChannel() {
        if (this.channel == null) {
            return;
        }
        try {
            this.channel.close();
        } catch (final IOException | TimeoutException exception) {
            this.logger.warn("Failed to close the Shards server startup channel", exception);
        } finally {
            this.channel = null;
        }
    }

    private void closeConnection() {
        if (this.connection == null) {
            return;
        }
        try {
            this.connection.close();
        } catch (final IOException exception) {
            this.logger.warn("Failed to close the Shards server startup connection", exception);
        } finally {
            this.connection = null;
        }
    }
}
