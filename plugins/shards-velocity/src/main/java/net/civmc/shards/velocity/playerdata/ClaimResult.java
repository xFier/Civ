package net.civmc.shards.velocity.playerdata;

import net.civmc.shards.api.PlayerLocation;
import java.util.UUID;

/**
 * Outcome of claiming ownership of a player's data.
 *
 * <p>Kept distinct from the payload deliberately: signalling "new player" or "refused" through
 * reserved byte values in the payload itself would be ambiguous with real data starting with those
 * bytes.</p>
 */
public sealed interface ClaimResult {

    /**
     * Claim succeeded and the player has stored data. {@code location} is null if the data was
     * written without one.
     */
    record Loaded(byte[] payload, PlayerLocation location) implements ClaimResult {
    }

    /**
     * Claim succeeded and nobody has stored data for this player before, so the destination server
     * should generate it.
     */
    record NewPlayer() implements ClaimResult {
    }

    /**
     * Refused: another server owns this player's data and has not written it back yet.
     */
    record HeldBy(UUID server) implements ClaimResult {
    }
}
