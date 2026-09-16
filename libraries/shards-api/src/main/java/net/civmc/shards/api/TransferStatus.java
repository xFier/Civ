package net.civmc.shards.api;

public enum TransferStatus {

    /** Saved, released, and the player has been asked to connect to the destination. */
    TRANSFERRED,

    /**
     * No shard owns the target location. Unowned ground is a valid configuration, so this is a wall
     * rather than an error: the player stays where they are.
     */
    NO_DESTINATION,

    /** The destination shard is not registered with the proxy, or refused the connection. */
    DESTINATION_UNAVAILABLE,

    /** The save was refused, so nothing was released and the player must not be moved. */
    SAVE_REFUSED,

    /** The proxy could not carry the transfer out. */
    ERROR
}
