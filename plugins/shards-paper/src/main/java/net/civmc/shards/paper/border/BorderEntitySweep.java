package net.civmc.shards.paper.border;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Vehicle;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.util.Vector;

/**
 * Stops the things that drift over a border and have no move event to refuse.
 *
 * <p>Mobs are caught by {@code EntityMoveEvent} and vehicles by {@code VehicleMoveEvent}, both of which
 * say where the entity came from as well as where it is going. <strong>Nothing else has one.</strong> A
 * dropped item, an arrow, primed TNT, a falling block and an experience orb all move with no event of
 * any kind, so the only way to notice one crossing is to look.</p>
 *
 * <p>What is at stake is not the same for each. An arrow past the line is litter. <strong>An item is
 * somebody's</strong> - it drifts onto ground this server is not authoritative for, the shard that owns
 * that ground cannot see it, and it despawns there five minutes later.</p>
 *
 * <p>Only a <em>crossing</em> is refused. Each pass remembers where every one of these entities was
 * last seen inside, and an entity found outside is put back only if there is such a memory of it.
 * Something that was already out there has none, and is left exactly alone - the same rule as
 * {@code EntityMoveEvent}, and the same reason the unowned entity view hides rather than removes.
 * The memory is rebuilt from scratch every pass, so nothing accumulates and a dead entity is forgotten
 * by not being seen.</p>
 *
 * <p>Only chunks at a border are looked at, and only while they are loaded, which is what keeps this
 * affordable: the work is set by how much border has somebody near it, not by how big the world is or
 * how many entities are in it. A chunk is a border chunk when it and one of its four neighbours
 * disagree about who owns them - tested once when the chunk loads, not every pass.</p>
 *
 * <p>Both sides of the line are watched. An entity that has crossed is no longer in a chunk of ours, so
 * watching only our own would be watching the one place it is guaranteed not to be.</p>
 */
public final class BorderEntitySweep implements Listener {

    private static final long REPORT_NANOS = 30L * 1_000_000_000L;

    private final ShardBorder border;
    private final Logger logger;

    // Border chunks that are loaded right now, by world. Maintained as chunks load and unload rather
    // than asked for each pass: a border can be thousands of chunks long and almost none of it is
    // loaded at any moment
    private final Map<String, Set<Long>> watching = new ConcurrentHashMap<>();
    // Where each of these entities was last seen on our side. Replaced every pass, never added to
    private Map<UUID, Location> wasInside = new HashMap<>();

    private long sweptNanos;
    private long passes;
    private long worstNanos;
    private long stopped;
    private long reportedAtNanos = System.nanoTime();

    public BorderEntitySweep(final ShardBorder border, final Logger logger) {
        this.border = border;
        this.logger = logger;
    }

    /**
     * Takes note of a chunk that has just loaded, if it is one at a border.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(final ChunkLoadEvent event) {
        if (isAtABorder(event.getChunk().getX(), event.getChunk().getZ())) {
            this.watching.computeIfAbsent(event.getWorld().getName(),
                ignored -> ConcurrentHashMap.newKeySet()).add(packed(event.getChunk()));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(final ChunkUnloadEvent event) {
        final Set<Long> chunks = this.watching.get(event.getWorld().getName());
        if (chunks != null) {
            chunks.remove(packed(event.getChunk()));
        }
    }

    /**
     * Picks up the chunks that were already loaded before this started watching.
     *
     * <p>The areas arrive from the proxy after the worlds do, so the spawn chunks - and anything a
     * player has already loaded during the handshake - would otherwise never be noticed.</p>
     */
    public void watchWhatIsAlreadyLoaded(final Iterable<World> worlds) {
        for (final World world : worlds) {
            for (final Chunk chunk : world.getLoadedChunks()) {
                if (isAtABorder(chunk.getX(), chunk.getZ())) {
                    this.watching.computeIfAbsent(world.getName(),
                        ignored -> ConcurrentHashMap.newKeySet()).add(packed(chunk));
                }
            }
        }
    }

    /**
     * One pass. Main thread, every tick.
     */
    public void sweep(final Iterable<World> worlds) {
        if (!this.border.isConfigured()) {
            return;
        }
        final long startedAt = System.nanoTime();
        final Map<UUID, Location> nowInside = new HashMap<>();
        for (final World world : worlds) {
            sweepWorld(world, nowInside);
        }
        this.wasInside = nowInside;
        record(System.nanoTime() - startedAt);
    }

    private void sweepWorld(final World world, final Map<UUID, Location> nowInside) {
        final Set<Long> chunks = this.watching.get(world.getName());
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        for (final long packed : chunks) {
            final int chunkX = (int) (packed >> 32);
            final int chunkZ = (int) packed;
            // A chunk can be unloaded without the event having been seen yet, and asking for it by
            // coordinate would load it back - a sweep that keeps the whole border in memory
            if (!world.isChunkLoaded(chunkX, chunkZ)) {
                continue;
            }
            for (final Entity entity : world.getChunkAt(chunkX, chunkZ).getEntities()) {
                consider(entity, nowInside);
            }
        }
    }

    private void consider(final Entity entity, final Map<UUID, Location> nowInside) {
        if (!drifts(entity)) {
            return;
        }
        final Location at = entity.getLocation();
        if (!this.border.isOutside(at)) {
            nowInside.put(entity.getUniqueId(), at);
            return;
        }
        final Location wasAt = this.wasInside.get(entity.getUniqueId());
        if (wasAt == null) {
            // Never seen on our side, so it did not cross - it was already out there, and it is one of
            // this shard's own. Dragging it in would be inventing a delivery nobody asked for
            return;
        }
        entity.setVelocity(new Vector());
        entity.teleport(wasAt, PlayerTeleportEvent.TeleportCause.PLUGIN);
        this.stopped++;
    }

    /**
     * Whether this is one of the entities nothing else can refuse.
     *
     * <p>Living entities have {@code EntityMoveEvent} and vehicles have {@code VehicleMoveEvent}, both
     * of them exact and free; doing it twice would fight them. Hanging entities - frames and paintings -
     * do not move at all. A passenger moves because whatever carries it does, so it is answered by
     * refusing the carrier and never by being put back on its own, which would take it out of the
     * vehicle it is in.</p>
     */
    private static boolean drifts(final Entity entity) {
        return !(entity instanceof LivingEntity)
            && !(entity instanceof Vehicle)
            && !(entity instanceof Hanging)
            && entity.getVehicle() == null;
    }

    /**
     * Whether a chunk has a border running beside it, on either side of the line.
     *
     * <p>Shard edges fall on chunk boundaries, so a chunk is owned or not owned as a whole and this is
     * five comparisons. Both sides count: an entity that has crossed is in a chunk that is <em>not</em>
     * ours, so watching only our own would watch the one place it cannot be.</p>
     */
    private boolean isAtABorder(final int chunkX, final int chunkZ) {
        if (!this.border.isConfigured()) {
            return false;
        }
        final boolean outside = this.border.isChunkOutside(chunkX, chunkZ);
        return outside != this.border.isChunkOutside(chunkX + 1, chunkZ)
            || outside != this.border.isChunkOutside(chunkX - 1, chunkZ)
            || outside != this.border.isChunkOutside(chunkX, chunkZ + 1)
            || outside != this.border.isChunkOutside(chunkX, chunkZ - 1);
    }

    private static long packed(final Chunk chunk) {
        return ((long) chunk.getX() << 32) | (chunk.getZ() & 0xffffffffL);
    }

    /**
     * Says what the sweep is costing, every half minute, and only when it has done something.
     *
     * <p>Reported because it is the one piece of this that runs every tick whether anything is
     * happening or not, and because what it costs depends on how much border has somebody standing
     * near it - which is not a number anybody can work out in advance.</p>
     */
    private void record(final long nanos) {
        this.sweptNanos += nanos;
        this.passes++;
        this.worstNanos = Math.max(this.worstNanos, nanos);
        if (System.nanoTime() - this.reportedAtNanos < REPORT_NANOS) {
            return;
        }
        this.reportedAtNanos = System.nanoTime();
        if (this.passes > 0L && this.sweptNanos > 0L) {
            this.logger.fine(String.format(
                "Border entity sweep: %.3fms a tick on average, worst %.3fms, %d entit(ies) put back",
                this.sweptNanos / 1_000_000.0 / this.passes, this.worstNanos / 1_000_000.0,
                this.stopped));
        }
        this.sweptNanos = 0L;
        this.passes = 0L;
        this.worstNanos = 0L;
        this.stopped = 0L;
    }
}
