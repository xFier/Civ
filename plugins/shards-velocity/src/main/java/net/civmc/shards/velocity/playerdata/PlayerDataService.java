package net.civmc.shards.velocity.playerdata;

import net.civmc.shards.api.PlayerLocation;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import net.civmc.shards.velocity.database.PlayerDataRow;
import net.civmc.shards.velocity.database.PlayerDataStatements;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;

/**
 * Single-owner access to stored player data.
 *
 * <p>Exactly one server may own a player's data at a time: {@link #claim} takes ownership and hands
 * out the payload, {@link #saveAndRelease} writes it back and gives ownership up. A server that
 * does not hold ownership cannot write, which is what stops the same inventory being live in two
 * places.</p>
 */
@Singleton
public final class PlayerDataService {

    private static final String INTEGRITY_CONSTRAINT_VIOLATION = "23";

    private final Jdbi jdbi;

    @Inject
    public PlayerDataService(final Jdbi jdbi) {
        this.jdbi = jdbi;
    }

    /**
     * Takes ownership of a player's data for {@code serverUuid}.
     */
    public ClaimResult claim(final UUID playerUuid, final UUID serverUuid) {
        try {
            return claimOnce(playerUuid, serverUuid);
        } catch (final UnableToExecuteStatementException exception) {
            if (!isDuplicateKey(exception)) {
                throw exception;
            }
            // Another proxy inserted the prepared row between our select and our insert. Retrying
            // finds their row and reports the player as held, rather than failing the login.
            return claimOnce(playerUuid, serverUuid);
        }
    }

    private ClaimResult claimOnce(final UUID playerUuid, final UUID serverUuid) {
        return this.jdbi.inTransaction(handle -> {
            final PlayerDataStatements statements = handle.attach(PlayerDataStatements.class);
            final Optional<PlayerDataRow> existing = statements.selectForUpdate(playerUuid);
            final ClaimResult result;
            if (existing.isEmpty()) {
                statements.insertClaim(playerUuid, serverUuid);
                result = new ClaimResult.NewPlayer();
            } else {
                final PlayerDataRow row = existing.get();
                if (row.owningServerUuid() != null) {
                    result = new ClaimResult.HeldBy(row.owningServerUuid());
                } else {
                    statements.claim(playerUuid, serverUuid);
                    result = new ClaimResult.Loaded(row.payload(), row.location());
                }
            }
            return result;
        });
    }

    /**
     * Writes a player's data back and releases ownership. Only succeeds if {@code serverUuid} still
     * owns the player.
     *
     * @param location may be null, leaving the stored location untouched as null
     */
    public SaveResult saveAndRelease(final UUID playerUuid, final UUID serverUuid, final byte[] payload,
                                     final PlayerLocation location) {
        return this.jdbi.inTransaction(handle -> {
            final PlayerDataStatements statements = handle.attach(PlayerDataStatements.class);
            final int updated = statements.saveAndRelease(
                playerUuid,
                serverUuid,
                payload,
                location == null ? null : location.world(),
                location == null ? null : location.x(),
                location == null ? null : location.y(),
                location == null ? null : location.z());
            if (updated > 0) {
                return new SaveResult.Success();
            }
            // Nothing matched, so re-read to say why rather than returning a bare failure
            final Optional<PlayerDataRow> row = statements.select(playerUuid);
            final SaveResult result;
            if (row.isEmpty()) {
                result = new SaveResult.NoRow();
            } else if (row.get().owningServerUuid() == null) {
                result = new SaveResult.NotHeld();
            } else {
                result = new SaveResult.HeldByOther(row.get().owningServerUuid());
            }
            return result;
        });
    }

    /**
     * Clears a stale lock left behind by a server that died holding one, discarding nothing: the
     * stored payload is whatever was last written back.
     *
     * @return whether a lock was actually held
     */
    public boolean forceRelease(final UUID playerUuid) {
        return this.jdbi.withExtension(PlayerDataStatements.class,
            statements -> statements.forceRelease(playerUuid) > 0);
    }

    /**
     * Clears every lock held by one server. Only safe when the caller knows that server has no
     * players online - at its startup, where anything still held under its id belongs to the run
     * before. At any other moment this hands a live player's data to a second writer.
     *
     * <p>Nothing is discarded: the stored payload stays whatever was last written back.</p>
     *
     * @return how many stale locks were cleared
     */
    public int releaseAllForServer(final UUID serverUuid) {
        return this.jdbi.withExtension(PlayerDataStatements.class,
            statements -> statements.releaseAllForServer(serverUuid));
    }

    /**
     * Gives up one player's lock without writing anything back, and only if {@code serverUuid} holds
     * it. For ownership taken at pre-login for a player who then never arrived.
     *
     * @return whether a lock held by that server was actually dropped
     */
    public boolean release(final UUID playerUuid, final UUID serverUuid) {
        return this.jdbi.withExtension(PlayerDataStatements.class,
            statements -> statements.release(playerUuid, serverUuid) > 0);
    }

    private static boolean isDuplicateKey(final UnableToExecuteStatementException exception) {
        // MariaDB reports 23000, H2 23505; both are the integrity-constraint class
        return exception.getCause() instanceof SQLException cause
            && cause.getSQLState() != null
            && cause.getSQLState().startsWith(INTEGRITY_CONSTRAINT_VIOLATION);
    }
}
