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
    public static final String PLAYER_CHECKPOINT_QUEUE = "shards.playerdata.checkpoint";
    public static final boolean PLAYER_QUEUE_DURABLE = true;

    // Not durable: a probe is about where a player is standing right now, so one that outlived a
    // broker restart would be answered long after it stopped being a question anybody had
    public static final String BORDER_PROBE_QUEUE = "shards.border.probe";
    public static final boolean BORDER_PROBE_QUEUE_DURABLE = false;

    public static final String REPLY_QUEUE_PREFIX = "shards.replies.";

    /**
     * Where a server's replies are delivered.
     *
     * <p>Named after the server rather than left for the broker to name. A broker-generated name
     * changes when a client recovers its connection, and anything holding the old one addresses
     * replies to a queue that no longer exists - which fails silently, because an unroutable message
     * is discarded rather than refused.</p>
     */
    public static String replyQueue(final String serverName) {
        return REPLY_QUEUE_PREFIX + serverName;
    }

    private ShardsRabbitMqTopology() {
    }
}
