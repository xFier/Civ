package net.civmc.zorweth.transfer;

import net.civmc.shards.api.PlayerLocation;
import net.civmc.shards.paper.ShardsPaperPlugin;
import net.civmc.shards.paper.border.TransferService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Moving a player to another shard, through the one implementation of it.
 *
 * <p>Rocket launches, pioneering and cross-server one-time teleports used to each move players their
 * own way - clearing them here and rebuilding them at the far end out of their own tables. They now
 * hand the player over intact, so nothing has to be taken apart and put back together, and there is
 * no window where their things exist in a table and nowhere else.</p>
 *
 * <p>A player's state travels exactly as it is. Where arriving with nothing is the point, as it is for
 * pioneering, the player is emptied <strong>before</strong> being handed over - so that stays a rule of
 * pioneering rather than something the transfer does on its own.</p>
 */
public final class ShardTransfers {

    private ShardTransfers() {
    }

    /**
     * Sends a player to a place on another shard, given by that shard's world and coordinates.
     *
     * @return whether the transfer was started; false leaves the player exactly where they are
     */
    public static boolean toLocation(final Player player, final String world, final double x, final double y,
                                     final double z) {
        return service()
            .map(transfers -> transfers.transferTo(player, new PlayerLocation(world, x, y, z)))
            .orElse(false);
    }

    /**
     * Sends a player to a place on another shard that this server can name as a {@link Location},
     * which means its world exists here too.
     */
    public static boolean toLocation(final Player player, final Location location) {
        return service().map(transfers -> transfers.transferTo(player, location)).orElse(false);
    }

    /**
     * Sends a player to a shard and lets that server decide where they appear, the way it does for
     * anyone arriving for the first time.
     */
    public static boolean toShard(final Player player, final String shardName) {
        return service().map(transfers -> transfers.transferToShard(player, shardName)).orElse(false);
    }

    private static java.util.Optional<TransferService> service() {
        // Shards is a hard dependency, so it is loaded; it can still be mid-enable or have failed its
        // own startup, and a transfer attempted then must fail rather than half-happen
        if (Bukkit.getPluginManager().getPlugin("Shards") instanceof ShardsPaperPlugin shards) {
            return shards.getTransfers();
        }
        return java.util.Optional.empty();
    }
}
