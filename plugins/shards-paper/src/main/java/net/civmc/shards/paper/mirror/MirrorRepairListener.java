package net.civmc.shards.paper.mirror;

import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Draws the mirror again after a client has rubbed a piece of it out.
 *
 * <p>A client predicts its own block placements and breaks, and the server corrects it when it
 * refuses one. The correction says what is <em>really</em> there - which, for ground on another
 * shard, is this server's own untouched copy, not what the mirror drew. So placing a block against a
 * mirrored wall makes the wall vanish: the client is being told the truth, and the truth is that
 * those blocks are not here.</p>
 *
 * <p>Nothing has gone wrong on either server when this happens. The neighbour's wall is untouched,
 * and nothing was placed or broken anywhere - the border refused it, as it refuses everything past
 * the edge. It is only the picture that is lost, and only for the one player who tried.</p>
 *
 * <p>So the picture is simply drawn again, by forgetting that they were shown that chunk. The next
 * pass sends it back, which is under a second.</p>
 */
public final class MirrorRepairListener implements Listener {

    private final JavaPlugin plugin;
    private final MirrorView mirror;

    public MirrorRepairListener(final JavaPlugin plugin, final MirrorView mirror) {
        this.plugin = plugin;
        this.mirror = mirror;
    }

    /**
     * Monitor, and deliberately <strong>not</strong> {@code ignoreCancelled}: the cancelled ones are
     * the whole point. An action that went through changed this server's own ground, which the mirror
     * never drew over.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlace(final BlockPlaceEvent event) {
        repairIfRefused(event.getPlayer(), event.getBlock(), event.isCancelled());
        // The block they clicked on is the mirrored one, and it can be in a different chunk from the
        // one they tried to fill
        repairIfRefused(event.getPlayer(), event.getBlockAgainst(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBreak(final BlockBreakEvent event) {
        repairIfRefused(event.getPlayer(), event.getBlock(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(final PlayerInteractEvent event) {
        if (event.hasBlock()) {
            repairIfRefused(event.getPlayer(), event.getClickedBlock(), event.isCancelled());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketEmpty(final PlayerBucketEmptyEvent event) {
        repairIfRefused(event.getPlayer(), event.getBlock(), event.isCancelled());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onBucketFill(final PlayerBucketFillEvent event) {
        repairIfRefused(event.getPlayer(), event.getBlock(), event.isCancelled());
    }

    private void repairIfRefused(final Player player, final Block block, final boolean cancelled) {
        if (!cancelled || block == null) {
            return;
        }
        // A tick later, so the repair lands after the server's own correction rather than racing it.
        // Sending first would only mean drawing the mirror and then having it wiped again
        this.plugin.getServer().getScheduler().runTask(this.plugin,
            () -> this.mirror.redraw(player, block.getX() >> 4, block.getZ() >> 4));
    }
}
