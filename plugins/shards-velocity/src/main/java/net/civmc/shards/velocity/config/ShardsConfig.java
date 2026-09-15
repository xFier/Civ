package net.civmc.shards.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * config.yml. All keys are kebab-case versions of the component names, e.g. holdingServer -> holding-server.
 */
@ConfigSerializable
public record ShardsConfig(
    String holdingServer,
    String failureMessage,
    String mainServer,
    String zorwethServer,
    String defaultServer,
    DatabaseConfig database
) {

    public ShardsConfig {
        if (database == null) {
            throw new IllegalStateException("Missing database config section");
        }
        // Absent keys arrive as null, so fall back to the previous defaults
        holdingServer = holdingServer == null ? "" : holdingServer.trim();
        failureMessage = failureMessage == null
            ? "Unable to verify your rocket transfer. Please reconnect and try again."
            : failureMessage;
        mainServer = mainServer == null ? "main" : mainServer;
        zorwethServer = zorwethServer == null ? "zorweth" : zorwethServer;
        defaultServer = defaultServer == null ? mainServer : defaultServer;
    }

    /**
     * Loads config.yml from the data directory, writing the bundled default first if it doesn't exist.
     */
    public static ShardsConfig load(final Path dataDirectory) {
        try {
            if (!Files.exists(dataDirectory)) {
                Files.createDirectories(dataDirectory);
            }
            final Path configFile = dataDirectory.resolve("config.yml");
            if (!Files.exists(configFile)) {
                try (InputStream input = ShardsConfig.class.getResourceAsStream("/config.yml")) {
                    if (input == null) {
                        throw new IllegalStateException("Default config.yml is missing");
                    }
                    Files.copy(input, configFile);
                }
            }
            final ShardsConfig config = YamlConfigurationLoader.builder()
                .path(configFile)
                // Maps nodes onto @ConfigSerializable records, e.g. connection-timeout -> connectionTimeout
                .defaultOptions(options -> options.serializers(serializers -> serializers.registerAnnotatedObjects(
                    ObjectMapper.factoryBuilder().defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED).build())))
                .build()
                .load()
                .get(ShardsConfig.class);
            if (config == null) {
                throw new IllegalStateException("config.yml is empty");
            }
            return config;
        } catch (final IOException exception) {
            // Includes SerializationException, e.g. a required database key is missing
            throw new RuntimeException("Could not load Shards Velocity config", exception);
        }
    }
}
