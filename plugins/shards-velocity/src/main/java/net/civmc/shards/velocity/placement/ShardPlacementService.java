package net.civmc.shards.velocity.placement;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.civmc.shards.velocity.config.ShardRegion;
import net.civmc.shards.velocity.config.ShardsConfig;
import net.civmc.shards.velocity.database.PlayerDataRow;
import net.civmc.shards.velocity.database.PlayerDataStatements;
import net.civmc.shards.velocity.playerdata.PlayerLocation;
import org.jdbi.v3.core.Jdbi;

/**
 * Works out which shard owns a set of coordinates.
 *
 * <p>Shards divide the x/z plane, not the world list: every world at a given coordinate belongs to
 * the same shard, so the world a location names is not consulted. See {@link ShardRegion} for how
 * block ownership is decided at a border.</p>
 */
@Singleton
public final class ShardPlacementService {

    private final ShardsConfig shardsConfig;
    private final Jdbi jdbi;

    @Inject
    public ShardPlacementService(final ShardsConfig shardsConfig, final Jdbi jdbi) {
        this.shardsConfig = shardsConfig;
        this.jdbi = jdbi;
    }

    /**
     * @return the shard owning {@code location}, or empty if no configured shard does
     */
    public Optional<String> shardFor(final PlayerLocation location) {
        // Floor rather than cast: a cast truncates towards zero, which would put someone standing at
        // x = -0.5 on block 0 instead of block -1, and so on the wrong side of a border at zero
        final int blockX = (int) Math.floor(location.x());
        final int blockZ = (int) Math.floor(location.z());
        for (final Map.Entry<String, List<ShardRegion>> shardEntry : this.shardsConfig.shards().entrySet()) {
            for (final ShardRegion region : shardEntry.getValue()) {
                if (region.containsBlock(blockX, blockZ)) {
                    return Optional.of(shardEntry.getKey());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * @return the shard owning the player's stored location, or empty if they have none stored or it
     *     falls outside every configured shard
     */
    public Optional<String> shardForPlayer(final UUID playerUuid) {
        // One statement, so a handle per call is fine here, unlike the claim path in PlayerDataService
        final Optional<PlayerDataRow> playerDataRow = this.jdbi.withExtension(PlayerDataStatements.class,
            statements -> statements.select(playerUuid));
        // map() already yields empty for a row that has never been written back, since its
        // location() is null then
        return playerDataRow.map(PlayerDataRow::location).flatMap(this::shardFor);
    }

    public boolean isShard(final String serverName) {
        return this.shardsConfig.shards().containsKey(serverName);
    }

    public Set<String> shardNames() {
        return this.shardsConfig.shards().keySet();
    }
}
