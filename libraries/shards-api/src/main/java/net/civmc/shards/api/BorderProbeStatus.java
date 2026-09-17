package net.civmc.shards.api;

/**
 * What lies on the other side of one border face.
 *
 * <p>A shard learns only its own areas, so it knows where it ends but not what it ends against. These
 * are the answers that matter to somebody standing at the edge, because they are the difference
 * between a door, a wall, and a door that is shut today.</p>
 */
public enum BorderProbeStatus {

    /** Another shard owns it and is answering, so crossing should work. */
    CROSSABLE,

    /** No shard owns it. Shards are allowed not to touch, so this is a wall by design, not a fault. */
    UNOWNED,

    /** A shard owns it but is not answering, so crossing would be refused until it comes back. */
    UNREACHABLE
}
