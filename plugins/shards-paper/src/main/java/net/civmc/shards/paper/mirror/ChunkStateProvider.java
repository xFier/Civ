package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.civmc.shards.api.mirror.ChunkSectionState;
import net.civmc.shards.api.mirror.MirroredEntity;
import net.civmc.shards.api.mirror.MirroredSign;
import net.civmc.shards.api.mirror.ChunkState;
import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;

/**
 * Answers a neighbour asking what is really in one of this shard's chunks.
 *
 * <p>Reading a chunk is the expensive half of the mirror - sixteen thousand blocks per section - so
 * the only thing done on the main thread is taking the snapshot, which is a copy. Everything after it
 * runs off the main thread, which is exactly what {@link ChunkSnapshot} exists for.</p>
 *
 * <p>Refused for a chunk this shard owns no part of. The copy of a neighbour's ground that this server
 * happens to hold is not truth about it, and serving it as though it were would spread one shard's
 * stale copy across the network.</p>
 */
public final class ChunkStateProvider {

    private final ShardBorder border;
    private final ChunkRevisions revisions;

    public ChunkStateProvider(final ShardBorder border, final ChunkRevisions revisions) {
        this.border = border;
        this.revisions = revisions;
    }

    /**
     * @param waitNanos getting hold of the chunk: loading it if it was not in memory, then waiting for
     *     a tick to take the snapshot on. Latency, not work - the server is idle for most of it
     * @param buildNanos turning the snapshot into sections. Real work, off the main thread
     */
    public record ChunkRead(ChunkState state, long waitNanos, long buildNanos, String publisherId,
                            long revision, List<MirroredEntity> entities, List<MirroredSign> signs) {
    }

    /**
     * A snapshot and the announcement number in force when it was taken, read together on the main
     * thread so nothing can be announced between the two. See {@link ChunkRevisions}.
     */
    private record Taken(ChunkSnapshot snapshot, long revision, List<MirroredEntity> entities,
                         List<MirroredSign> signs) {
    }

    /**
     * Reads one chunk, loading it if it is not already in memory.
     *
     * @return the chunk's contents, or a failed future when this shard cannot honestly answer
     */
    public CompletableFuture<ChunkRead> read(final String worldName, final int chunkX, final int chunkZ) {
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No world named " + worldName + " on this server"));
        }
        if (!ownsChunk(chunkX, chunkZ)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("This shard owns no part of chunk " + chunkX + ", " + chunkZ));
        }
        final long askedAt = System.nanoTime();
        final ChunkKey key = new ChunkKey(worldName, chunkX, chunkZ);
        return world.getChunkAtAsync(chunkX, chunkZ)
            // Still on the main thread, and deliberately only this: a snapshot is a copy, and copying
            // is all the main thread should be asked to do for somebody else's rendering. The
            // announcement number is read here too, because taken anywhere else it could be a number
            // either side of this snapshot rather than the one that matches it
            .thenApply(chunk -> new Taken(chunk.getChunkSnapshot(), this.revisions.current(key),
                StillEntities.in(chunk), Signs.in(chunk)))
            .thenApplyAsync(taken -> {
                final long snapshotAt = System.nanoTime();
                final ChunkState state = toState(taken.snapshot(), world.getMinHeight(),
                    world.getMaxHeight());
                return new ChunkRead(state, snapshotAt - askedAt, System.nanoTime() - snapshotAt,
                    this.revisions.publisherId(), taken.revision(), taken.entities(), taken.signs());
            });
    }

    /**
     * Whether this chunk belongs to us. One test, not a scan: shard edges fall on chunk boundaries, so
     * a chunk is never split and every block in it answers the same.
     */
    private boolean ownsChunk(final int chunkX, final int chunkZ) {
        // No areas means this server is not a shard at all - the holding server is the usual case -
        // and it owns everywhere as far as the border is concerned
        return !this.border.isConfigured() || !this.border.isChunkOutside(chunkX, chunkZ);
    }

    private static ChunkState toState(final ChunkSnapshot snapshot, final int minHeight, final int maxHeight) {
        final int sectionCount = (maxHeight - minHeight) / 16;
        final List<ChunkSectionState> sections = new ArrayList<>(sectionCount);
        for (int section = 0; section < sectionCount; section++) {
            sections.add(snapshot.isSectionEmpty(section)
                ? ChunkSectionState.empty()
                : readSection(snapshot, minHeight + (section << 4)));
        }
        return new ChunkState(minHeight, sections);
    }

    private static ChunkSectionState readSection(final ChunkSnapshot snapshot, final int baseY) {
        // Insertion ordered, so an index is the position of a state in the list and the two are built
        // in one pass
        final Map<String, Short> paletteIndices = new LinkedHashMap<>();
        final short[] indices = new short[ChunkSectionState.BLOCKS_PER_SECTION];
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    final String blockData = snapshot.getBlockData(x, baseY + y, z).getAsString();
                    indices[ChunkSectionState.indexOf(x, y, z)] = paletteIndices.computeIfAbsent(
                        blockData, ignored -> (short) paletteIndices.size());
                }
            }
        }
        return new ChunkSectionState(List.copyOf(paletteIndices.keySet()), indices);
    }
}
