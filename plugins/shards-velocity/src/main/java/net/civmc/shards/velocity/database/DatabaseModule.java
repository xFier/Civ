package net.civmc.shards.velocity.database;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;
import net.civmc.shards.velocity.config.DatabaseConfig;
import net.civmc.shards.velocity.config.ShardsConfig;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;
import org.jdbi.v3.core.mapper.reflect.ConstructorMapper;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;

public final class DatabaseModule extends AbstractModule {

    @Provides
    @Singleton
    public DatabaseConfig databaseConfig(final ShardsConfig shardsConfig) {
        return shardsConfig.database();
    }

    @Provides
    @Singleton
    public HikariDataSource dataSource(final DatabaseConfig databaseConfig, final ProxyServer proxyServer,
                                       final PluginContainer pluginContainer) {
        final HikariDataSource dataSource = databaseConfig.createDataSource();
        // Guice has no lifecycle hooks, so whatever opens the pool also arranges for it to be closed
        proxyServer.getEventManager().register(pluginContainer, ProxyShutdownEvent.class, event -> dataSource.close());
        try {
            // Runs before anything can query, and fails startup rather than leaving half a schema
            ShardsDatabase.migrate(dataSource);
        } catch (final SQLException exception) {
            dataSource.close();
            throw new IllegalStateException("Could not migrate the Shards database", exception);
        }
        return dataSource;
    }

    @Provides
    @Singleton
    public Jdbi jdbi(final HikariDataSource dataSource) {
        final Jdbi jdbi = Jdbi.create(dataSource).installPlugin(new SqlObjectPlugin());
        // Maps shard_player_data columns onto the PlayerDataRow record, owning_server -> owningServer
        jdbi.registerRowMapper(ConstructorMapper.factory(PlayerDataRow.class));
        // UUID columns are VARCHAR(36), so bind UUIDs as their string form
        jdbi.registerArgument(new AbstractArgumentFactory<UUID>(Types.VARCHAR) {
            @Override
            protected Argument build(final UUID value, final ConfigRegistry configRegistry) {
                return (position, statement, context) -> statement.setString(position, value.toString());
            }
        });
        return jdbi;
    }
}
