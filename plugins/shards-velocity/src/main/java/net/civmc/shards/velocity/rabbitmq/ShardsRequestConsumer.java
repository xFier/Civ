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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import org.slf4j.Logger;

/**
 * Consumes every request queue the proxy answers, dispatching each to its {@link RequestHandler}.
 *
 * <p>One consumer rather than one per queue: connecting, reconnecting, acking and correlating replies
 * is the same work every time, and having it written once means a queue cannot quietly get a different
 * version of it.</p>
 */
public final class ShardsRequestConsumer implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long RECONNECT_DELAY_SECONDS = 5L;
    // Deliveries are handled concurrently rather than one at a time: claims sit on the login path, and
    // a prefetch of one would queue every login on the network behind every other. The handlers keep
    // no state between calls, and each does one database transaction
    private static final int PREFETCH_COUNT = 16;

    private final ConnectionFactory connectionFactory;
    private final List<RequestHandler<?, ?>> handlers;
    private final ProxyServer proxyServer;
    private final Object plugin;
    private final Logger logger;
    private Connection connection;
    // One per queue. The driver dispatches a channel's deliveries in order, one at a time, so sharing
    // a channel would put every queue behind whichever request is slowest - and the queues answer each
    // other, so that is not merely slow, it can be a standstill
    private final List<Channel> channels = new ArrayList<>();

    public ShardsRequestConsumer(final ConnectionFactory connectionFactory,
                                 final List<RequestHandler<?, ?>> handlers, final ProxyServer proxyServer,
                                 final Object plugin, final Logger logger) {
        this.connectionFactory = connectionFactory;
        this.handlers = List.copyOf(handlers);
        this.proxyServer = proxyServer;
        this.plugin = plugin;
        this.logger = logger;
    }

    public boolean start() {
        return connect();
    }

    private synchronized boolean connect() {
        try {
            this.connection = this.connectionFactory.newConnection("shards-velocity");
            watchBlocking(this.connection);
            for (final RequestHandler<?, ?> handler : this.handlers) {
                consume(handler);
            }
            this.logger.info("Shards request consumer connected, serving {} queues", this.handlers.size());
            return true;
        } catch (final ConnectException exception) {
            this.logger.warn("Retrying RabbitMQ connection for the Shards request consumer");
            this.proxyServer.getScheduler().buildTask(this.plugin, this::connect)
                .delay(RECONNECT_DELAY_SECONDS, TimeUnit.SECONDS)
                .schedule();
            return false;
        } catch (final IOException | TimeoutException exception) {
            this.logger.error("Failed to connect the Shards request consumer", exception);
            return false;
        }
    }

    /**
     * Says so when the broker stops accepting the replies this consumer publishes.
     *
     * <p>A resource alarm - the broker running low on disk or memory - blocks publishers instead of
     * refusing them. Requests still arrive and are still handled, so this side looks healthy, while
     * every reply is held and every server waiting for one runs to its timeout. Diagnosed once from
     * queue statistics, which is not a reasonable thing to need; this makes it a log line.</p>
     */
    private void watchBlocking(final Connection connection) {
        connection.addBlockedListener(
            reason -> this.logger.error("RabbitMQ has blocked this connection ({}). Requests will be "
                + "handled but no reply can be sent, so every server will time out. Check the broker's "
                + "free disk space and memory", reason),
            () -> this.logger.info("RabbitMQ has unblocked this connection; replies resume"));
    }

    private <REQ, RES> void consume(final RequestHandler<REQ, RES> handler) throws IOException {
        final Channel channel = this.connection.createChannel();
        this.channels.add(channel);
        channel.basicQos(PREFETCH_COUNT);
        // A reply addressed to a queue that has gone is otherwise discarded in silence, and the sender
        // just waits out its timeout with nothing anywhere saying why
        channel.addReturnListener(returned -> this.logger.error(
            "A reply to {} could not be delivered: {}. The sender will time out",
            returned.getRoutingKey(), returned.getReplyText()));
        channel.queueDeclare(handler.queue(), handler.durable(), false, false, null);
        final DeliverCallback deliverCallback = (consumerTag, delivery) -> handleDelivery(
            handler, channel, delivery.getBody(), delivery.getProperties(), delivery.getEnvelope().getDeliveryTag());
        channel.basicConsume(handler.queue(), false, deliverCallback, consumerTag -> {
        });
    }

    private <REQ, RES> void handleDelivery(final RequestHandler<REQ, RES> handler, final Channel channel,
                                           final byte[] body, final AMQP.BasicProperties properties,
                                           final long deliveryTag) throws IOException {
        // Parsing and handling are separate because a body we cannot read and a request we could not
        // carry out are different answers, and because anything escaping here would leave the delivery
        // unacked to be redelivered forever
        REQ request = null;
        RES response = null;
        try {
            request = GSON.fromJson(new String(body, StandardCharsets.UTF_8), handler.requestType());
            if (request == null) {
                throw new JsonParseException("Empty body");
            }
        } catch (final RuntimeException exception) {
            // Caught as RuntimeException, not JsonParseException: the record's own validation runs
            // during deserialization, and Gson reports a constructor that rejected its arguments as a
            // plain RuntimeException
            this.logger.warn("Rejecting malformed request on {}", handler.queue(), exception);
            final UUID requestId = parseCorrelationId(properties);
            if (requestId != null) {
                response = handler.failure(requestId, "Malformed request");
            }
        }

        if (request != null) {
            try {
                response = handler.handle(request);
            } catch (final RuntimeException exception) {
                this.logger.error("Request on {} failed", handler.queue(), exception);
                response = handler.failure(handler.requestId(request), "Request failed");
            }
        }

        if (response != null) {
            publishResponse(channel, properties, response);
        }
        channel.basicAck(deliveryTag, false);
    }

    private void publishResponse(final Channel channel, final AMQP.BasicProperties requestProperties,
                                 final Object response) throws IOException {
        if (requestProperties == null || requestProperties.getReplyTo() == null
            || requestProperties.getReplyTo().isBlank()) {
            this.logger.warn("Dropping a response because no reply queue was provided");
            return;
        }
        final AMQP.BasicProperties responseProperties = new AMQP.BasicProperties.Builder()
            .contentType(ShardsRabbitMqTopology.CONTENT_TYPE_JSON)
            .correlationId(requestProperties.getCorrelationId())
            .deliveryMode(1)
            .build();
        // mandatory, so an undeliverable reply comes back to the listener above rather than vanishing
        channel.basicPublish("", requestProperties.getReplyTo(), true, responseProperties,
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
        for (final Channel channel : this.channels) {
            try {
                channel.close();
            } catch (final IOException | TimeoutException exception) {
                this.logger.warn("Failed to close a Shards channel", exception);
            }
        }
        this.channels.clear();
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (final IOException exception) {
                this.logger.warn("Failed to close the Shards connection", exception);
            } finally {
                this.connection = null;
            }
        }
    }
}
