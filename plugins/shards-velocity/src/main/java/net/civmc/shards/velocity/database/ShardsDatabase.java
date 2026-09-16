package net.civmc.shards.velocity.database;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import net.civmc.nameapi.Migrator;

public final class ShardsDatabase {

    private ShardsDatabase() {
    }

    public static void migrate(final DataSource dataSource) throws SQLException {
        final Migrator migrator = new Migrator();
        // Own namespace, so this runs independently of zorweth's migrations in the same database
        migrator.registerMigration("shards", 0,
            """
                CREATE TABLE IF NOT EXISTS shard_player_data (
                    player_uuid VARCHAR(36) NOT NULL,
                    payload LONGBLOB,
                    owning_server_uuid VARCHAR(36),
                    world VARCHAR(64),
                    x DOUBLE,
                    y DOUBLE,
                    z DOUBLE,
                    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
                    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
                    PRIMARY KEY (player_uuid),
                    INDEX idx_shard_player_data_owning_server_uuid (owning_server_uuid)
                )
                """);

        try (Connection connection = dataSource.getConnection()) {
            migrator.migrate(connection);
        }
    }
}
