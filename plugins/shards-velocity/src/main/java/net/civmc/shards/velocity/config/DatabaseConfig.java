package net.civmc.shards.velocity.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * The {@code database} section of config.yml (see {@link ShardsConfig} for key naming).
 */
@ConfigSerializable
public record DatabaseConfig(
    String host,
    int port,
    String database,
    String user,
    String password,
    int poolSize,
    long connectionTimeout,
    long idleTimeout,
    long maxLifetime
) {

    public DatabaseConfig {
        // Required: a missing value should stop startup, not silently connect to localhost as root
        host = requireNonBlank(host, "host");
        database = requireNonBlank(database, "database");
        user = requireNonBlank(user, "user");

        // Optional: absent keys arrive as null/0, so fall back to the previous defaults
        password = password == null ? "" : password;
        port = port > 0 ? port : 3306;
        poolSize = poolSize > 0 ? poolSize : 5;
        connectionTimeout = connectionTimeout > 0 ? connectionTimeout : 10_000L;
        idleTimeout = idleTimeout > 0 ? idleTimeout : 600_000L;
        maxLifetime = maxLifetime > 0 ? maxLifetime : 7_200_000L;
    }

    public HikariDataSource createDataSource() {
        final HikariConfig config = new HikariConfig();
        // The queries are MariaDB-specific, so the driver isn't configurable. Naming the driver class also means
        // Hikari loads it directly instead of relying on DriverManager, which doesn't discover drivers bundled
        // inside a plugin jar (hence the old Class.forName)
        config.setDriverClassName("org.mariadb.jdbc.Driver");
        config.setJdbcUrl("jdbc:mariadb://" + this.host + ":" + this.port + "/" + this.database);
        config.setUsername(this.user);
        if (!this.password.isBlank()) {
            config.setPassword(this.password);
        }
        config.setMaximumPoolSize(this.poolSize);
        config.setConnectionTimeout(this.connectionTimeout);
        config.setIdleTimeout(this.idleTimeout);
        config.setMaxLifetime(this.maxLifetime);
        return new HikariDataSource(config);
    }

    private static String requireNonBlank(final String value, final String key) {
        if (Objects.requireNonNullElse(value, "").isBlank()) {
            throw new IllegalArgumentException("database." + key + " must be set");
        }
        return value.trim();
    }
}
