package net.civmc.shards.paper.mirror;

import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.mirror.MirroredEntity;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;

/**
 * Tells the other shards when a frame or a stand near this one's border has changed.
 *
 * <p>Without this a shop filled on one shard appears on its neighbour whenever that chunk next happens
 * to be read, which is the ten-minute backstop. This is the same step the blocks took when the block
 * announcements were written, for the same reason: a change nobody can see for ten minutes reads as a
 * broken mirror rather than a slow one.</p>
 *
 * <p><strong>The whole chunk is described, not what changed.</strong> There are a handful of these per
 * chunk against sixteen thousand blocks a section, so describing all of them costs less than working
 * out which one was touched - and it means a removal needs no message of its own, because something
 * absent from the list is gone. It also sidesteps every event's disagreement about whether it carries
 * the entity before or after: the chunk is read a tick later and what is really there is what is
 * sent.</p>
 *
 * <p>Numbered from the same count as the block announcements, so a viewer notices a missed one of
 * either in exactly the same way.</p>
 *
 * <p>A chunk that has <em>unloaded</em> by the time this reads it is skipped, and that is not a detail.
 * Removal fires when a chunk unloads as well as when something is broken, so reading an unloaded chunk
 * would find nothing and announce that a neighbour's shop had been emptied, for no better reason than
 * that nobody was standing near it.</p>
 */
public final class MirrorEntityPublisher implements Listener {

    // The same reach as the block announcements: far enough for a neighbour on a generous view
    // distance, and it costs only bandwidth because a receiver drops what it is not looking at
    private static final int PUBLISH_RADIUS = 256;

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    private final ChunkRevisions revisions;
    // Chunks whose still entities changed this tick, read at the end of it. A set, so a frame filled
    // and rotated in one tick is described once
    private final Set<ChunkKey> dirty = new HashSet<>();

    public MirrorEntityPublisher(final ShardBorder border, final ShardsClient client,
                                 final String serverName, final ChunkRevisions revisions) {
        this.border = border;
        this.client = client;
        this.serverName = serverName;
        this.revisions = revisions;
    }

    /**
     * Describes every chunk that changed this tick. Main thread, once a tick.
     */
    public void flush() {
        if (this.dirty.isEmpty()) {
            return;
        }
        final Set<ChunkKey> changed = Set.copyOf(this.dirty);
        this.dirty.clear();
        for (final ChunkKey key : changed) {
            final World world = Bukkit.getWorld(key.world());
            if (world == null || !world.isChunkLoaded(key.x(), key.z())) {
                // See the class comment: an unloaded chunk reads as empty, and announcing that would
                // empty a neighbour's shop because nobody was standing near it
                continue;
            }
            final List<MirroredEntity> still = StillEntities.in(world.getChunkAt(key.x(), key.z()));
            this.client.publishMirrorUpdate(ChunkUpdateMessage.entities(this.serverName, key.world(),
                key.x(), key.z(), still, this.revisions.publisherId(), this.revisions.next(key)));
        }
    }

    /**
     * Notes that something happened to an entity worth describing, if anyone else could be looking.
     */
    private void changed(final Entity entity) {
        if (entity == null || !this.border.isConfigured()) {
            return;
        }
        if (!(entity instanceof ItemFrame) && !(entity instanceof ArmorStand)) {
            return;
        }
        final int blockX = entity.getLocation().getBlockX();
        final int blockZ = entity.getLocation().getBlockZ();
        // Ours, and near enough to an edge that another shard could be drawing it
        if (this.border.isOutside(blockX, blockZ)
            || !this.border.outlineWithin(blockX, blockZ, PUBLISH_RADIUS)) {
            return;
        }
        this.dirty.add(new ChunkKey(entity.getWorld().getName(), blockX >> 4, blockZ >> 4));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSpawn(final EntitySpawnEvent event) {
        changed(event.getEntity());
    }

    /**
     * Also fires when a chunk unloads, which is why {@link #flush} refuses to read an unloaded chunk.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRemove(final EntityRemoveFromWorldEvent event) {
        changed(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(final HangingPlaceEvent event) {
        changed(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingBreak(final HangingBreakEvent event) {
        changed(event.getEntity());
    }

    /**
     * Putting something into a frame, or turning what is in it.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(final PlayerInteractEntityEvent event) {
        changed(event.getRightClicked());
    }

    /**
     * Taking something out of a frame is a hit, not a click.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(final EntityDamageByEntityEvent event) {
        changed(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onArmorStand(final PlayerArmorStandManipulateEvent event) {
        changed(event.getRightClicked());
    }
}
