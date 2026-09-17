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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.ChunkStateRequest;
import net.civmc.shards.api.ChunkStateResponse;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.PlayerPositionMessage;
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
    private final Consumer<ChunkUpdateMessage> updates;
    private final Consumer<PlayerPositionMessage> positions;
    private volatile boolean closed;
    private Connection connection;
    // One per consumer. The driver dispatches a channel's deliveries on a single thread, in order, so
    // everything sharing a channel queues behind whichever delivery is slowest - and a chunk read is
    // by far the slowest thing here. Sharing one channel meant a shard that was serving chunks stopped
    // receiving announcements, which expire in seconds, so neighbours silently went stale until
    // something forced a re-read. The proxy's consumer learned this first and says so in its own
    // comment; this is the same lesson, one layer along
    private final List<Channel> channels = new ArrayList<>();
    private Channel chunkChannel;

    public ShardsServer(final ConnectionFactory connectionFactory, final String serverName,
                        final JavaPlugin plugin, final Logger logger, final ChunkStateProvider chunks,
                        final MirrorMetrics metrics, final Consumer<ChunkUpdateMessage> updates,
                        final Consumer<PlayerPositionMessage> positions) {
        this.connectionFactory = connectionFactory;
        this.serverName = serverName;
        this.plugin = plugin;
        this.logger = logger;
        this.chunks = chunks;
        this.metrics = metrics;
        this.updates = updates;
        this.positions = positions;
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
            this.chunkChannel = newChannel();
            this.chunkChannel.basicQos(PREFETCH_COUNT);
            final Map<String, Object> arguments = new HashMap<>();
            arguments.put("x-message-ttl", ShardsRabbitMqTopology.MIRROR_REQUEST_TTL_MILLIS);
            // Durable and not exclusive, like every other request queue here: RabbitMQ refuses a
            // transient non-exclusive queue at the connection level, which costs the whole connection
            // rather than the one declare
            this.chunkChannel.queueDeclare(ShardsRabbitMqTopology.mirrorQueue(this.serverName),
                ShardsRabbitMqTopology.REQUEST_QUEUE_DURABLE, false, false, arguments);
            final DeliverCallback deliverCallback = (consumerTag, delivery) -> handleDelivery(
                delivery.getBody(), delivery.getProperties(), delivery.getEnvelope().getDeliveryTag());
            this.chunkChannel.basicConsume(ShardsRabbitMqTopology.mirrorQueue(this.serverName), false,
                deliverCallback, consumerTag -> {
                });
            consumeAnnouncements(ShardsRabbitMqTopology.MIRROR_UPDATE_EXCHANGE, ChunkUpdateMessage.class,
                ChunkUpdateMessage::serverName, this.updates);
            consumeAnnouncements(ShardsRabbitMqTopology.MIRROR_PLAYER_EXCHANGE, PlayerPositionMessage.class,
                PlayerPositionMessage::serverName, this.positions);
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

    /**
     * Listens for what the other shards announce has changed.
     *
     * <p>An exclusive, auto-deleting queue of our own bound to the fanout. Exclusive is allowed where
     * the request queues could not be, because this one belongs to this connection and goes away with
     * it - which is what we want, since an announcement held for a server that is not running would
     * arrive after that server had already re-read the chunk.</p>
     */
    private <T> void consumeAnnouncements(final String exchange, final Class<T> type,
                                          final Function<T, String> sender, final Consumer<T> handler)
        throws IOException {
        // Its own channel, so an announcement is never queued behind a chunk read
        final Channel announcements = newChannel();
        announcements.exchangeDeclare(exchange, "fanout", false);
        final String queue = announcements.queueDeclare().getQueue();
        announcements.queueBind(queue, exchange, "");
        announcements.basicConsume(queue, true, (consumerTag, delivery) -> {
            try {
                final T message = GSON.fromJson(new String(delivery.getBody(), StandardCharsets.UTF_8), type);
                if (message != null && !sender.apply(message).equals(this.serverName)) {
                    // Our own announcements come back to us, because a fanout goes to everybody
                    handler.accept(message);
                }
            } catch (final RuntimeException exception) {
                this.logger.log(Level.FINE, "Dropping a malformed announcement on " + exchange, exception);
            }
        }, consumerTag -> {
        });
    }

    private void handleDelivery(final byte[] body, final AMQP.BasicProperties properties,
                                final long deliveryTag) {
        try {
            answer(GSON.fromJson(new String(body, StandardCharsets.UTF_8), ChunkStateRequest.class),
                properties, deliveryTag);
        } catch (final RuntimeException exception) {
            // Anything escaping here would leave the delivery unacked and redelivered forever, and the
            // asker waiting out a timeout with nothing saying why
            this.logger.log(Level.WARNING, "Could not read a chunk request", exception);
            final UUID requestId = parseCorrelationId(properties);
            finish(properties, requestId == null ? null : ChunkStateResponse.error(requestId, "Request failed"),
                deliveryTag);
        }
    }

    /**
     * Reads a chunk and replies when it is ready, without holding on to the delivery thread.
     *
     * <p>Waiting here would serialise every chunk this shard serves behind the one before it - a band
     * of seventy chunks at ninety milliseconds each is six seconds of a neighbour's border filling in
     * one chunk at a time. The read is already asynchronous; this just stops undoing that.</p>
     */
    private void answer(final ChunkStateRequest request, final AMQP.BasicProperties properties,
                        final long deliveryTag) {
        this.chunks.read(request.world(), request.chunkX(), request.chunkZ())
            .orTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .whenComplete((read, error) -> {
                ChunkStateResponse response;
                if (error != null) {
                    // Not worth shouting about: a neighbour asking about a chunk this shard owns no
                    // part of is the ordinary answer to a border probe a moment out of date
                    this.logger.log(Level.FINE, "Refused a chunk request", error);
                    response = ChunkStateResponse.error(request.requestId(), "Could not read that chunk");
                } else {
                    try {
                        final long encodeStartedAt = System.nanoTime();
                        final byte[] encoded = ChunkStateCodec.toBytes(read.state());
                        // Three parts, because they are not the same thing: waiting for a chunk is
                        // latency spent idle, while building and encoding is work actually done. One
                        // figure made a mirror that is merely slow to fill look like an expensive one
                        this.metrics.served(read.waitNanos(), read.buildNanos(),
                            System.nanoTime() - encodeStartedAt, encoded.length);
                        response = ChunkStateResponse.of(request.requestId(), request.world(),
                            request.chunkX(), request.chunkZ(),
                            Base64.getEncoder().encodeToString(encoded));
                    } catch (final RuntimeException exception) {
                        this.logger.log(Level.WARNING, "Could not encode a chunk", exception);
                        response = ChunkStateResponse.error(request.requestId(), "Could not encode that chunk");
                    }
                }
                finish(properties, response, deliveryTag);
            });
    }

    /**
     * Replies and acknowledges, on whichever thread the read finished on.
     *
     * <p>Both under the channel's own lock: a {@link Channel} is not safe to use from two threads, and
     * several reads can now finish at once.</p>
     */
    private void finish(final AMQP.BasicProperties properties, final ChunkStateResponse response,
                        final long deliveryTag) {
        try {
            synchronized (this.chunkChannel) {
                publish(properties, response);
                this.chunkChannel.basicAck(deliveryTag, false);
            }
        } catch (final IOException | RuntimeException exception) {
            // An unacked delivery is redelivered forever, so this is worth seeing
            this.logger.log(Level.WARNING, "Could not answer a chunk request", exception);
        }
    }

    private void publish(final AMQP.BasicProperties requestProperties, final ChunkStateResponse response)
        throws IOException {
        if (response == null) {
            return;
        }
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
        this.chunkChannel.basicPublish("", requestProperties.getReplyTo(), responseProperties,
            GSON.toJson(response).getBytes(StandardCharsets.UTF_8));
    }

    private Channel newChannel() throws IOException {
        final Channel created = this.connection.createChannel();
        this.channels.add(created);
        return created;
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
        for (final Channel open : this.channels) {
            try {
                open.close();
            } catch (final IOException | TimeoutException | AlreadyClosedException exception) {
                this.logger.log(Level.FINE, "Failed to close a Shards server channel", exception);
            }
        }
        this.channels.clear();
        this.chunkChannel = null;
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
