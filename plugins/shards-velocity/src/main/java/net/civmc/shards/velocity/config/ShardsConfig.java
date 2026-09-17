package net.civmc.shards.velocity.config;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.civmc.shards.api.region.ShardRegion;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;
import org.spongepowered.configurate.objectmapping.ObjectMapper;
import org.spongepowered.configurate.util.NamingSchemes;
import org.spongepowered.configurate.yaml.YamlConfigurationLoader;

/**
 * config.yml. All keys are kebab-case versions of the component names, e.g. holdingServer -> holding-server.
 *
 * @param shards the areas each shard owns, keyed by the server name the shard runs on
 * @param holdingServer where a player whose shard cannot be determined is sent
 * @param networkList whether this proxy answers /list for the whole network, taking the name off
 *     the shards, each of which knows only its own players
 * @param lockExpirySeconds how long a shard must go without answering before the players it is
 *     holding are let go. Zero leaves them held until it comes back, which is what happened before
 *     this key existed
 */
@ConfigSerializable
public record ShardsConfig(
    Map<String, List<ShardRegion>> shards,
    String holdingServer,
    String failureMessage,
    Integer lockExpirySeconds,
    Boolean networkList,
    DatabaseConfig database,
    RabbitMqConfig rabbitmq
) {

    public ShardsConfig {
        if (database == null) {
            throw new IllegalStateException("Missing database config section");
        }
        // Required, unlike an optional section: the servers reach their player data only through this
        // proxy, so one that cannot answer them is not a degraded proxy, it is a proxy nobody can use
        if (rabbitmq == null) {
            throw new IllegalStateException("Missing rabbitmq config section");
        }
        // Absent keys arrive as null, so fall back to the previous defaults
        holdingServer = holdingServer == null ? "" : holdingServer.trim();
        // On by default: a network that reads as several half-empty servers is the thing the shards
        // are meant to hide, and a proxy that answers this is the only side that can
        networkList = networkList == null || networkList;
        // A minute of silence: long enough that a shard pausing under load is not mistaken for a dead
        // one, short enough that somebody logging in after a crash is not left waiting on an operator
        lockExpirySeconds = lockExpirySeconds == null ? 60 : lockExpirySeconds;
        failureMessage = failureMessage == null
            ? "Unable to place you on a shard. Please reconnect and try again."
            : failureMessage;
        // A proxy with no shards configured yet should still start; it just places nobody
        shards = shards == null
            ? Map.of()
            // Not Map.copyOf: two shards can no longer claim the same block, but a stable iteration
            // order still keeps startup errors and lookups reproducible between restarts
            : Collections.unmodifiableMap(new LinkedHashMap<>(shards));
        requireNoOverlap(shards);
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
            final ShardsConfig shardsConfig = YamlConfigurationLoader.builder()
                .path(configFile)
                // Maps nodes onto @ConfigSerializable records, e.g. connection-timeout -> connectionTimeout
                .defaultOptions(options -> options.serializers(serializers -> serializers.registerAnnotatedObjects(
                    ObjectMapper.factoryBuilder().defaultNamingScheme(NamingSchemes.LOWER_CASE_DASHED).build())))
                .build()
                .load()
                .get(ShardsConfig.class);
            if (shardsConfig == null) {
                throw new IllegalStateException("config.yml is empty");
            }
            return shardsConfig;
        } catch (final IOException exception) {
            // Includes SerializationException, e.g. a required database key is missing
            throw new RuntimeException("Could not load Shards Velocity config", exception);
        }
    }

    /**
     * Two shards owning the same block would put one player's data on either of them depending on
     * config order, so this refuses to start rather than warning. {@link ShardRegion#overlaps} is
     * exact, so there are no false positives to work around: shards sharing a border are fine.
     */
    private static void requireNoOverlap(final Map<String, List<ShardRegion>> shards) {
        final List<Map.Entry<String, ShardRegion>> shardRegions = shards.entrySet().stream()
            .flatMap(shardEntry -> shardEntry.getValue().stream()
                .map(region -> Map.entry(shardEntry.getKey(), region)))
            .toList();
        for (int firstIndex = 0; firstIndex < shardRegions.size(); firstIndex++) {
            for (int secondIndex = firstIndex + 1; secondIndex < shardRegions.size(); secondIndex++) {
                final Map.Entry<String, ShardRegion> firstShard = shardRegions.get(firstIndex);
                final Map.Entry<String, ShardRegion> secondShard = shardRegions.get(secondIndex);
                // A shard may cover the same ground twice with its own areas; only two different
                // shards claiming it is ambiguous
                if (firstShard.getKey().equals(secondShard.getKey())) {
                    continue;
                }
                if (firstShard.getValue().overlaps(secondShard.getValue())) {
                    throw new IllegalArgumentException(
                        "Shards " + firstShard.getKey() + " and " + secondShard.getKey() + " both own blocks in "
                            + firstShard.getValue().boundingBox().intersection(secondShard.getValue().boundingBox()));
                }
            }
        }
    }
}
