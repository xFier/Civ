package net.civmc.shards.api;

/**
 * Names of the queues the proxy and its servers exchange messages over.
 *
 * <p>There is a queue per operation rather than one queue carrying a tagged union. The bodies have
 * little in common - a save carries a payload, a claim does not - so a single request type would be
 * mostly fields that are null for every operation but one, and would need a polymorphic adapter to
 * deserialize. A queue each keeps every message a plain record.</p>
 */
public final class ShardsRabbitMqTopology {

    public static final String CONTENT_TYPE_JSON = "application/json";

    public static final String SERVER_STARTUP_QUEUE = "shards.server.startup";
    public static final boolean SERVER_STARTUP_QUEUE_DURABLE = true;

    public static final String PLAYER_CLAIM_QUEUE = "shards.playerdata.claim";
    public static final String PLAYER_SAVE_QUEUE = "shards.playerdata.save";
    public static final String PLAYER_RELEASE_QUEUE = "shards.playerdata.release";
    public static final String PLAYER_TRANSFER_QUEUE = "shards.playerdata.transfer";
    public static final boolean PLAYER_QUEUE_DURABLE = true;

    private ShardsRabbitMqTopology() {
    }
}
