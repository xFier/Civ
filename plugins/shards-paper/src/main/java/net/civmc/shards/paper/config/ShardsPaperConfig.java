package net.civmc.shards.paper.config;

import com.rabbitmq.client.ConnectionFactory;
import java.util.Objects;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml.
 *
 * @param serverName the name this server is registered under in the proxy, which is also the
 *     identity it owns player data under
 * @param failureMessage what a player is told when their data cannot be claimed or restored
 * @param saveIntervalSeconds how often a player who is still playing is written back. Zero turns it
 *     off, which means a server that is killed rather than stopped loses everything since they arrived
 * @param arrivalTitle MiniMessage shown to a player crossing in from another shard, blank for none
 * @param arrivalSubtitle MiniMessage shown beneath it, blank for none
 */
public record ShardsPaperConfig(String serverName, String failureMessage, int saveIntervalSeconds,
                                String arrivalTitle, String arrivalSubtitle,
                                String user, String password, String host, int port) {

    public ShardsPaperConfig {
        serverName = requireNonBlank(serverName, "server-name");
        failureMessage = failureMessage == null || failureMessage.isBlank()
            ? "Unable to load your player data. Please reconnect and try again."
            : failureMessage;
        if (saveIntervalSeconds < 0) {
            throw new IllegalArgumentException("save-interval-seconds must not be negative");
        }
        arrivalTitle = arrivalTitle == null ? "" : arrivalTitle;
        arrivalSubtitle = arrivalSubtitle == null ? "" : arrivalSubtitle;
        user = requireNonBlank(user, "rabbitmq.user");
        password = password == null ? "" : password;
        host = requireNonBlank(host, "rabbitmq.host");
        if (port <= 0) {
            throw new IllegalArgumentException("rabbitmq.port must be positive");
        }
    }

    public static ShardsPaperConfig from(final FileConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        final ConfigurationSection rabbitmq = configuration.getConfigurationSection("rabbitmq");
        if (rabbitmq == null) {
            throw new IllegalStateException("Missing rabbitmq config section");
        }
        // Optional, unlike rabbitmq: a shard with nothing to say on arrival simply says nothing
        final ConfigurationSection arrival = configuration.getConfigurationSection("arrival");
        return new ShardsPaperConfig(
            configuration.getString("server-name"),
            configuration.getString("failure-message"),
            configuration.getInt("save-interval-seconds", 60),
            arrival == null ? "" : arrival.getString("title", ""),
            arrival == null ? "" : arrival.getString("subtitle", ""),
            rabbitmq.getString("user", "guest"),
            rabbitmq.getString("password", "guest"),
            rabbitmq.getString("host", "localhost"),
            rabbitmq.getInt("port", 5672));
    }

    public ConnectionFactory connectionFactory() {
        final ConnectionFactory factory = new ConnectionFactory();
        factory.setUsername(this.user);
        factory.setPassword(this.password);
        factory.setHost(this.host);
        factory.setPort(this.port);
        return factory;
    }

    private static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }
}
