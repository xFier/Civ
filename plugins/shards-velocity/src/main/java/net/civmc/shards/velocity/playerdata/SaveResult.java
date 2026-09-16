package net.civmc.shards.velocity.playerdata;

import java.util.UUID;

/**
 * Outcome of writing a player's data back and releasing ownership.
 *
 * <p>The three failure cases are kept apart because they mean different things operationally: a
 * missing row is a bug in the caller, an unowned row is a duplicate save, and a row owned by
 * someone else is the case that would have corrupted data had it been allowed through.</p>
 */
public sealed interface SaveResult {

    record Success() implements SaveResult {
    }

    /**
     * No row at all for this player — nothing ever claimed them.
     */
    record NoRow() implements SaveResult {
    }

    /**
     * The row exists but is unowned, so this save arrived after ownership was already released.
     */
    record NotHeld() implements SaveResult {
    }

    /**
     * A different server owns this player. The payload was not written.
     */
    record HeldByOther(UUID server) implements SaveResult {
    }
}
