package net.civmc.shards.paper.mirror;

import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;

/**
 * Stops this shard populating ground it does not own.
 *
 * <p>The companion to {@link UnownedEntityView}, which only hides what is already there. This stops
 * it arriving: a shard spawning mobs past its border is filling a neighbour's land with creatures the
 * neighbour cannot see, on top of the ones the neighbour really has there. Two herds in one field,
 * one of them invisible to half the network.</p>
 *
 * <p>Only the spawns the world decides on. A spawn asked for by a command or by another plugin is
 * somebody deliberately putting something somewhere, and refusing it would be this class overruling
 * an instruction rather than declining to invent one.</p>
 *
 * <p><strong>This changes what a player can farm.</strong> Mobs that used to spawn past the border
 * were never legitimately this shard's - the ground belongs to the neighbour, which spawns its own
 * there - so anything relying on both happening at once loses half its rate. Deliberate, and the
 * config key exists to turn it off while that is being judged.</p>
 */
public final class UnownedGroundListener implements Listener {

    private final ShardBorder border;

    public UnownedGroundListener(final ShardBorder border) {
        this.border = border;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onSpawn(final CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM
            || event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.COMMAND) {
            return;
        }
        if (this.border.isOutside(event.getLocation())) {
            event.setCancelled(true);
        }
    }
}
