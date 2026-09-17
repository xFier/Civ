package net.civmc.shards.paper.border;

import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Sends a player who dies here to whichever shard owns the place they respawn.
 *
 * <p>A respawn point does not have to be on the shard someone dies on: a bed is a block like any
 * other, and the shard that owns that block is the one that should have them. Without this the
 * respawn lands them at this shard's own spawn - outside their border, so their first step hands
 * them over anyway. That works, but it puts them somewhere they never chose and leaves whatever they
 * were respawning for a long walk away.</p>
 *
 * <p>The handover cannot happen during the respawn itself. The player is mid-respawn while the event
 * runs - no health yet, and no position - so a snapshot taken there would travel as a corpse. They
 * are respawned here first, at the place they died, and handed over on the tick after; the place
 * they died is used because it is the one position this shard is certain to own, having just had
 * them standing on it.</p>
 */
public final class ShardRespawnListener implements Listener {

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final TransferService transfers;
    private final Logger logger;

    public ShardRespawnListener(final JavaPlugin plugin, final ShardBorder border,
                                final TransferService transfers, final Logger logger) {
        this.plugin = plugin;
        this.border = border;
        this.transfers = transfers;
        this.logger = logger;
    }

    /**
     * Highest rather than monitor: every other plugin has had its say about where this player
     * belongs by now, so the location read here is the real answer - and it still has to be changed,
     * which monitor cannot do.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(final PlayerRespawnEvent event) {
        final Location respawn = event.getRespawnLocation();
        if (!this.border.isOutside(respawn)) {
            return;
        }
        final Player player = event.getPlayer();
        final Location target = respawn.clone();
        final Location here = player.getLocation();
        event.setRespawnLocation(here);
        this.logger.info("Respawn point of " + player.getUniqueId() + " is on another shard; putting them "
            + "back where they died and handing them over");
        Bukkit.getScheduler().runTask(this.plugin, () -> {
            if (!player.isOnline()) {
                return;
            }
            // Refusals answer for themselves - the player is told at the border and stays here, alive
            // where they died, which is no worse than the respawn they would have had before this
            this.transfers.transferTo(player, target);
        });
    }
}
