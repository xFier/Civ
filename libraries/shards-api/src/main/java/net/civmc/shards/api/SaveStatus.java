package net.civmc.shards.api;

public enum SaveStatus {

    /** Written back and ownership given up. */
    SAVED,

    /** Nobody held the lock, so nothing was written. */
    NOT_HELD,

    /** Another server holds the lock. Nothing was written, which is the point of the lock. */
    HELD_BY_OTHER,

    /** No row for this player at all. */
    NO_ROW,

    /** The proxy could not answer. */
    ERROR
}
