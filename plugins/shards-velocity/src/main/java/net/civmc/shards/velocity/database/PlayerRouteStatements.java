package net.civmc.shards.velocity.database;

import java.util.Optional;
import java.util.UUID;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

public interface PlayerRouteStatements {

    @SqlQuery("""
        SELECT expected_server
        FROM rocket_player_routes
        WHERE player_uuid = :playerUuid
        """)
    Optional<String> findExpectedServer(UUID playerUuid);

    @SqlUpdate("""
        INSERT INTO rocket_player_routes (player_uuid, expected_server)
        VALUES (:playerUuid, :expectedServer)
        ON DUPLICATE KEY UPDATE
            expected_server = VALUES(expected_server)
        """)
    void setExpectedServer(UUID playerUuid, String expectedServer);
}
