package net.civmc.shards.paper.config;

import com.rabbitmq.client.ConnectionFactory;
import java.util.Locale;
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
 * @param skySyncSeconds how often this server asks the proxy what the sky should look like. Zero
 *     leaves it running its own clock and weather, so a crossing can go from noon into a storm
 * @param hideUnownedEntities whether this server stops spawning and stops showing entities on
 *     ground it does not own, which it otherwise populates with a wrong copy the owning shard cannot
 *     see
 * @param freezeUnownedGround whether this server stops its own copy of the ground past its border
 *     from changing. Otherwise that copy keeps ticking - fluids flow, fire spreads, things grow - and
 *     can act on ground the shard really does own, from a copy nobody else can see
 * @param mirrorChunks whether this server shows what the neighbouring shard really has on the
 *     ground past the border, instead of its own untouched copy of it
 * @param saveMirror whether what the neighbours have said is kept across a restart. Without it a
 *     restart reads every border chunk again from nothing, and a neighbour that is down shows as this
 *     server's own untouched copy rather than the last thing it said
 * @param borderStyle what the border is drawn with
 */
public record ShardsPaperConfig(String serverName, String failureMessage, int saveIntervalSeconds,
                                String arrivalTitle, String arrivalSubtitle, int skySyncSeconds,
                                boolean hideUnownedEntities, boolean freezeUnownedGround,
                                boolean mirrorChunks, boolean saveMirror, BorderStyle borderStyle,
                                String user, String password, String host, int port) {

    /**
     * What the border is drawn with.
     *
     * <p>Two of them because they fail in opposite directions, and which matters depends on the
     * server: particles cost nothing and put nothing in the world, but a client set to minimal
     * particles sees no border at all; glass renders whatever that setting says, at the price of
     * being entities other plugins can see.</p>
     */
    public enum BorderStyle {
        PARTICLES,
        GLASS
    }

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
        if (skySyncSeconds < 0) {
            throw new IllegalArgumentException("sky-sync-seconds must not be negative");
        }
        borderStyle = borderStyle == null ? BorderStyle.PARTICLES : borderStyle;
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
            configuration.getInt("sky-sync-seconds", 5),
            configuration.getBoolean("hide-unowned-entities", true),
            configuration.getBoolean("freeze-unowned-ground", true),
            configuration.getBoolean("mirror-chunks", true),
            configuration.getBoolean("save-mirror", true),
            borderStyle(configuration.getString("border-style", "particles")),
            rabbitmq.getString("user", "guest"),
            rabbitmq.getString("password", "guest"),
            rabbitmq.getString("host", "localhost"),
            rabbitmq.getInt("port", 5672));
    }

    /**
     * Named rather than numbered so a typo is refused at startup with the options in the message,
     * instead of quietly falling back to a border the operator did not ask for.
     */
    private static BorderStyle borderStyle(final String configured) {
        try {
            return BorderStyle.valueOf(configured.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException exception) {
            throw new IllegalArgumentException("border-style must be one of particles, glass - not "
                + configured);
        }
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
