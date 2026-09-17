package net.civmc.shards.velocity.rabbitmq;

import java.util.Map;
import java.util.UUID;

/**
 * One request queue and what to do with what arrives on it.
 *
 * <p>Everything about actually consuming - connecting, reconnecting, acking, correlating the reply -
 * lives in {@link ShardsRequestConsumer}, so a new operation is this interface and nothing else.</p>
 *
 * @param <REQ> the request record, deserialized straight from the body
 * @param <RES> the response record, serialized straight into the reply
 */
public interface RequestHandler<REQ, RES> {

    String queue();

    /**
     * Broker arguments for this handler's queue, empty unless it needs any.
     *
     * <p>Where a handler whose requests stop being worth answering says so - with a message TTL,
     * rather than by asking for a transient queue, which RabbitMQ refuses at the cost of the whole
     * connection.</p>
     *
     * <p>These are fixed at declaration: changing one later means the declare stops matching the
     * queue that already exists, and the broker refuses it until the old queue is deleted.</p>
     */
    default Map<String, Object> arguments() {
        return Map.of();
    }

    Class<REQ> requestType();

    UUID requestId(REQ request);

    RES handle(REQ request);

    /**
     * The answer to send when the request could not be read or the handler threw. There is always an
     * answer: a sender left waiting for a message that will never come blocks a login until it times
     * out.
     */
    RES failure(UUID requestId, String message);
}
