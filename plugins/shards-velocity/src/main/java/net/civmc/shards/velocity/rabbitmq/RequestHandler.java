package net.civmc.shards.velocity.rabbitmq;

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
