package net.civmc.shards.api;

public enum ClaimStatus {

    /** The player has stored data and it is now owned by the requesting server. */
    LOADED,

    /**
     * No row existed, and one has been created owned by the requesting server. The requesting server
     * must read this as <strong>keep whatever is already on disk</strong>. Reading it as an
     * authoritative empty player wipes everyone at once the first time the table is empty.
     */
    NEW_PLAYER,

    /** Another server still holds the lock. The login cannot proceed. */
    HELD_BY_OTHER,

    /** The proxy could not answer. The login cannot proceed. */
    ERROR
}
