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

    public static final String BORDER_PROBE_QUEUE = "shards.border.probe";
    // A probe is about where a player is standing right now, so one still sitting in the queue
    // seconds later is answering a question nobody has any more. The sender has already given up on
    // it, and its reply would be dropped as unmatched, so it is dropped here instead
    public static final int BORDER_PROBE_TTL_MILLIS = 10_000;

    public static final String SKY_STATE_QUEUE = "shards.sky.state";
    // The sky moves on while a request waits, so an answer to one that has been sitting here would
    // put a shard behind by however long it sat. Dropped instead: the next poll is seconds away and
    // asks about now
    public static final int SKY_STATE_TTL_MILLIS = 10_000;
    public static final String NIGHT_SKIP_QUEUE = "shards.sky.nightskip";

    /**
     * Every request queue survives a broker restart, including the probe queue, whose messages are
     * worthless within seconds.
     *
     * <p>Not a judgement about the messages - it is that the alternative does not exist. A transient
     * queue that is not exclusive is a feature RabbitMQ now refuses, and refuses at the connection
     * level: declaring one does not fail that queue, it tears down the whole connection. Asking for
     * one cost this plugin its entire request consumer at startup and was recovered from silently
     * enough that only the probes stayed missing. Short-lived messages say so with a TTL instead.</p>
     */
    public static final boolean REQUEST_QUEUE_DURABLE = true;

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
