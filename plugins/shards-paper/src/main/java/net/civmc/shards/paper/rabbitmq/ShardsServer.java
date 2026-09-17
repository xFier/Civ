package net.civmc.shards.paper.rabbitmq;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.AlreadyClosedException;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DeliverCallback;
import java.io.IOException;
import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.ChunkStateRequest;
import net.civmc.shards.api.ChunkStateResponse;
import net.civmc.shards.api.ShardsRabbitMqTopology;
import net.civmc.shards.api.mirror.ChunkStateCodec;
import net.civmc.shards.paper.mirror.ChunkStateProvider;
import net.civmc.shards.paper.mirror.MirrorMetrics;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Answers questions other shards ask this one.
 *
 * <p>The counterpart to {@link ShardsClient}, and the first time a shard has had to be asked
 * anything. Everything before the mirror went through the proxy, which could answer it: the proxy
 * holds the shard map and the player data. It does not hold a world, so the one question it cannot
 * answer is what is actually in a chunk.</p>
 *
 * <p>Only the mirror uses this, and only for reads. Nothing here writes anything, takes a lock or
 * moves a player - those stay with the proxy, so there is still exactly one place that decides who
 * owns what.</p>
 */
public final class ShardsServer implements AutoCloseable {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
    private static final long RECONNECT_DELAY_TICKS = 20L * 5L;
    // Handled a few at a time. Reading a chunk is real work, and a shard that let every neighbour's
    // request through at once would spend its snapshot budget on other people's scenery
    private static final int PREFETCH_COUNT = 4;
    // Longer than a chunk read should ever take, short enough that a stuck one does not hold a
    // consumer thread for the rest of the server's life
    private static final long READ_TIMEOUT_SECONDS = 20L;

    private final ConnectionFactory connectionFactory;
    private final String serverName;
    private final JavaPlugin plugin;
    private final Logger logger;
    private final ChunkStateProvider chunks;
    private final MirrorMetrics metrics;
    private volatile boolean closed;
    private Connection connection;
    private Channel channel;

    public ShardsServer(final ConnectionFactory connectionFactory, final String serverName,
                        final JavaPlugin plugin, final Logger logger, final ChunkStateProvider chunks,
                        final MirrorMetrics metrics) {
        this.connectionFactory = connectionFactory;
        this.serverName = serverName;
        this.plugin = plugin;
        this.logger = logger;
        this.chunks = chunks;
        this.metrics = metrics;
    }

    public boolean start() {
        return connect();
    }

    private synchronized boolean connect() {
        if (this.closed) {
            return false;
        }
        try {
            this.connection = this.connectionFactory.newConnection("shards-paper-server");
            this.channel = this.connection.createChannel();
            this.channel.basicQos(PREFETCH_COUNT);
            final Map<String, Object> arguments = new HashMap<>();
            arguments.put("x-message-ttl", ShardsRabbitMqTopology.MIRROR_REQUEST_TTL_MILLIS);
            // Durable and not exclusive, like every other request queue here: RabbitMQ refuses a
            // transient non-exclusive queue at the connection level, which costs the whole connection
            // rather than the one declare
            this.channel.queueDeclare(ShardsRabbitMqTopology.mirrorQueue(this.serverName),
                ShardsRabbitMqTopology.REQUEST_QUEUE_DURABLE, false, false, arguments);
            final DeliverCallback deliverCallback = (consumerTag, delivery) -> handleDelivery(
                delivery.getBody(), delivery.getProperties(), delivery.getEnvelope().getDeliveryTag());
            this.channel.basicConsume(ShardsRabbitMqTopology.mirrorQueue(this.serverName), false,
                deliverCallback, consumerTag -> {
                });
            this.logger.info("Answering chunk requests from other shards on "
                + ShardsRabbitMqTopology.mirrorQueue(this.serverName));
            return true;
        } catch (final ConnectException exception) {
            Bukkit.getScheduler().runTaskLaterAsynchronously(this.plugin, this::connect, RECONNECT_DELAY_TICKS);
            return false;
        } catch (final IOException | TimeoutException exception) {
            this.logger.log(Level.SEVERE, "Could not start answering other shards; they will see this one's "
                + "ground as whatever their own copy holds", exception);
            closeQuietly();
            Bukkit.getScheduler().runTaskLaterAsynchronously(this.plugin, this::connect, RECONNECT_DELAY_TICKS);
            return false;
        }
    }

    private void handleDelivery(final byte[] body, final AMQP.BasicProperties properties, final long deliveryTag)
        throws IOException {
        ChunkStateResponse response = null;
        try {
            final ChunkStateRequest request = GSON.fromJson(new String(body, StandardCharsets.UTF_8),
                ChunkStateRequest.class);
            response = answer(request);
        } catch (final RuntimeException exception) {
            // Anything escaping here would leave the delivery unacked and redelivered forever, and the
            // asker waiting out a timeout with nothing saying why
            this.logger.log(Level.WARNING, "Could not answer a chunk request", exception);
            final UUID requestId = parseCorrelationId(properties);
            if (requestId != null) {
                response = ChunkStateResponse.error(requestId, "Request failed");
            }
        }
        if (response != null) {
            publish(properties, response);
        }
        this.channel.basicAck(deliveryTag, false);
    }

    private ChunkStateResponse answer(final ChunkStateRequest request) {
        final long startedAt = System.nanoTime();
        try {
            final byte[] encoded = ChunkStateCodec.toBytes(
                this.chunks.read(request.world(), request.chunkX(), request.chunkZ())
                    .get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS));
            // Timed from here rather than from the chunk load, so this is the work this shard does for
            // a neighbour rather than however long it waited for a chunk to come off disk
            this.metrics.served(System.nanoTime() - startedAt, encoded.length);
            return ChunkStateResponse.of(request.requestId(), request.world(), request.chunkX(),
                request.chunkZ(), Base64.getEncoder().encodeToString(encoded));
        } catch (final InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ChunkStateResponse.error(request.requestId(), "Interrupted while reading the chunk");
        } catch (final ExecutionException | TimeoutException | RuntimeException exception) {
            // Not an error worth shouting about: a neighbour asking about a chunk this shard owns no
            // part of is the ordinary answer to a border probe that arrived a moment out of date
            this.logger.log(Level.FINE, "Refused a chunk request", exception);
            return ChunkStateResponse.error(request.requestId(), "Could not read that chunk");
        }
    }

    private void publish(final AMQP.BasicProperties requestProperties, final ChunkStateResponse response)
        throws IOException {
        if (requestProperties == null || requestProperties.getReplyTo() == null
            || requestProperties.getReplyTo().isBlank()) {
            this.logger.warning("Dropping a chunk answer because no reply queue was given");
            return;
        }
        final AMQP.BasicProperties responseProperties = new AMQP.BasicProperties.Builder()
            .contentType(ShardsRabbitMqTopology.CONTENT_TYPE_JSON)
            .correlationId(requestProperties.getCorrelationId())
            .deliveryMode(1)
            .build();
        synchronized (this) {
            this.channel.basicPublish("", requestProperties.getReplyTo(), responseProperties,
                GSON.toJson(response).getBytes(StandardCharsets.UTF_8));
        }
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
    public synchronized void close() {
        this.closed = true;
        closeQuietly();
    }

    private void closeQuietly() {
        if (this.channel != null) {
            try {
                this.channel.close();
            } catch (final IOException | TimeoutException | AlreadyClosedException exception) {
                this.logger.log(Level.FINE, "Failed to close the Shards server channel", exception);
            }
            this.channel = null;
        }
        if (this.connection != null) {
            try {
                this.connection.close();
            } catch (final IOException | AlreadyClosedException exception) {
                this.logger.log(Level.FINE, "Failed to close the Shards server connection", exception);
            }
            this.connection = null;
        }
    }
}
