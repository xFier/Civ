package net.civmc.shards.paper.mirror;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.civmc.shards.api.ChunkUpdateMessage;
import net.civmc.shards.api.mirror.MirroredSign;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Tells the other shards when a sign near this one's border has been written on.
 *
 * <p>The block announcements already say that a sign <em>is</em> there - a sign is a block - and say
 * nothing about what it says, because the text is not part of the block. So a shop that changes its
 * prices changed nothing any neighbour could see until that chunk was next read, which is the
 * ten-minute backstop. This is the same step the frames took, for the same reason.</p>
 *
 * <p><strong>The whole chunk's worth is described, not the one sign.</strong> A handful per chunk
 * against sixteen thousand blocks a section, so describing all of them is cheaper than working out
 * which one was written on - and a sign that has been broken needs no message of its own, because one
 * absent from the list is gone.</p>
 *
 * <p>Read a tick later rather than out of the event. {@code SignChangeEvent} carries the lines that
 * are <em>about</em> to be set, and the sign itself still says what it said; a tick later the block
 * is what it is going to be, which is the same trick the entity announcements use to sidestep every
 * event's disagreement about before and after.</p>
 *
 * <p>Numbered from the same count as the blocks and the frames, so a missed announcement of any of
 * them is noticed the same way.</p>
 */
public final class MirrorSignPublisher implements Listener {

    // The same reach as the other two: far enough for a neighbour on a generous view distance, and it
    // costs only bandwidth because a receiver drops what it is not looking at
    private static final int PUBLISH_RADIUS = 256;

    private final JavaPlugin plugin;
    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    private final ChunkRevisions revisions;
    // Chunks whose signs changed this tick, read at the end of it. A set, so two lines of the same
    // sign are described once
    private final Set<ChunkKey> dirty = new HashSet<>();

    public MirrorSignPublisher(final JavaPlugin plugin, final ShardBorder border,
                               final ShardsClient client, final String serverName,
                               final ChunkRevisions revisions) {
        this.plugin = plugin;
        this.border = border;
        this.client = client;
        this.serverName = serverName;
        this.revisions = revisions;
    }

    /**
     * Describes every chunk whose signs changed this tick. Main thread, once a tick.
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
                // An unloaded chunk reads as having no signs, and announcing that would wipe a
                // neighbour's street of shop signs because nobody was standing near it
                continue;
            }
            final List<MirroredSign> signs = Signs.in(world.getChunkAt(key.x(), key.z()));
            this.client.publishMirrorUpdate(ChunkUpdateMessage.signs(this.serverName, key.world(),
                key.x(), key.z(), signs, this.revisions.publisherId(), this.revisions.next(key)));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSignChange(final SignChangeEvent event) {
        changed(event.getBlock());
    }

    /**
     * A sign broken is a block change, which is announced already - but the list of what the chunk
     * says has one fewer sign in it, and nothing else would say so.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(final BlockBreakEvent event) {
        if (event.getBlock().getState(false) instanceof Sign) {
            changed(event.getBlock());
        }
    }

    /**
     * Notes that a sign somebody else could be reading has changed, a tick from now.
     */
    private void changed(final Block block) {
        if (!this.border.isConfigured()) {
            return;
        }
        // Ours, and near enough to an edge that another shard could be drawing it
        if (this.border.isOutside(block.getX(), block.getZ())
            || !this.border.outlineWithin(block.getX(), block.getZ(), PUBLISH_RADIUS)) {
            return;
        }
        this.plugin.getServer().getScheduler().runTask(this.plugin,
            () -> this.dirty.add(new ChunkKey(block.getWorld().getName(), block.getX() >> 4,
                block.getZ() >> 4)));
    }
}
