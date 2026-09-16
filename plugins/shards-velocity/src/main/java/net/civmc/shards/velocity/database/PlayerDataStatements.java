package net.civmc.shards.velocity.database;

import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.sqlobject.transaction.Transaction;

public interface PlayerDataStatements {

    /**
     * Takes a row lock for the rest of the transaction, which is what makes the claim atomic. Only
     * meaningful inside a transaction.
     */
    @SqlQuery("""
        SELECT payload, owning_server_uuid, world, x, y, z
        FROM shard_player_data
        WHERE player_uuid = :playerUuid
        FOR UPDATE
        """)
    Optional<PlayerDataRow> selectForUpdate(UUID playerUuid);

    @SqlQuery("""
        SELECT payload, owning_server_uuid, world, x, y, z
        FROM shard_player_data
        WHERE player_uuid = :playerUuid
        """)
    Optional<PlayerDataRow> select(UUID playerUuid);

    /**
     * Creates the "prepared" row for a player nobody has stored data for yet: owned, but with no
     * payload until the owning server writes one back.
     */
    @SqlUpdate("""
        INSERT INTO shard_player_data (player_uuid, owning_server_uuid)
        VALUES (:playerUuid, :owningServerUuid)
        """)
    void insertClaim(UUID playerUuid, UUID owningServerUuid);

    @SqlUpdate("""
        UPDATE shard_player_data
        SET owning_server_uuid = :owningServerUuid
        WHERE player_uuid = :playerUuid
        """)
    void claim(UUID playerUuid, UUID owningServerUuid);

    /**
     * Writes the payload back and releases ownership in one statement. The
     * {@code owning_server_uuid} predicate is the safety property: a server that does not hold the
     * lock updates no rows and cannot overwrite the holder's data.
     */
    @SqlUpdate("""
        UPDATE shard_player_data
        SET payload = :payload, owning_server_uuid = NULL, world = :world, x = :x, y = :y, z = :z
        WHERE player_uuid = :playerUuid AND owning_server_uuid = :owningServerUuid
        """)
    int saveAndRelease(UUID playerUuid, UUID owningServerUuid, byte[] payload, String world,
                       Double x, Double y, Double z);

    /**
     * Drops ownership without writing a payload. For stale locks left by a server that died while
     * holding one; the automatic recovery path needs the Paper side and does not exist yet.
     */
    @Transaction
    @SqlUpdate("""
        UPDATE shard_player_data
        SET owning_server_uuid = NULL
        WHERE player_uuid = :playerUuid AND owning_server_uuid IS NOT NULL
        """)
    int forceRelease(UUID playerUuid);

    /**
     * Drops every lock a server holds. Meant for a server that has just started and therefore has no
     * players online, so any lock still recorded against it was left behind by its predecessor.
     */
    @Transaction
    @SqlUpdate("""
        UPDATE shard_player_data
        SET owning_server_uuid = NULL
        WHERE owning_server_uuid = :owningServerUuid
        """)
    int releaseAllForServer(UUID owningServerUuid);
}
