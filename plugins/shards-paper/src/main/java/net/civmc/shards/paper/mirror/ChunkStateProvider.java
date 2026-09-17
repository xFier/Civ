package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.civmc.shards.api.mirror.ChunkSectionState;
import net.civmc.shards.api.mirror.ChunkState;
import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
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

    // Every fourth block in each direction. Enough to notice any notch worth drawing, and 16 tests
    // rather than 256 for a question asked of every chunk near a border
    private static final int OWNERSHIP_SAMPLE_STEP = 4;

    private final ShardBorder border;

    public ChunkStateProvider(final ShardBorder border) {
        this.border = border;
    }

    /**
     * Reads one chunk, loading it if it is not already in memory.
     *
     * @return the chunk's contents, or a failed future when this shard cannot honestly answer
     */
    public CompletableFuture<ChunkState> read(final String worldName, final int chunkX, final int chunkZ) {
        final World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("No world named " + worldName + " on this server"));
        }
        if (!ownsAnyOf(chunkX, chunkZ)) {
            return CompletableFuture.failedFuture(
                new IllegalArgumentException("This shard owns no part of chunk " + chunkX + ", " + chunkZ));
        }
        return world.getChunkAtAsync(chunkX, chunkZ)
            // Still on the main thread, and deliberately only this: a snapshot is a copy, and copying
            // is all the main thread should be asked to do for somebody else's rendering
            .thenApply(Chunk::getChunkSnapshot)
            .thenApplyAsync(snapshot -> toState(snapshot, world.getMinHeight(), world.getMaxHeight()));
    }

    /**
     * Whether any of this chunk belongs to us. Sampled rather than exhaustive - a chunk straddles a
     * border often enough to matter, but a shard area narrower than four blocks is not a thing anyone
     * is going to draw.
     */
    private boolean ownsAnyOf(final int chunkX, final int chunkZ) {
        if (!this.border.isConfigured()) {
            // No areas means this server is not a shard at all - the holding server is the usual case -
            // and it owns everywhere as far as the border is concerned
            return true;
        }
        for (int x = 0; x < 16; x += OWNERSHIP_SAMPLE_STEP) {
            for (int z = 0; z < 16; z += OWNERSHIP_SAMPLE_STEP) {
                if (!this.border.isOutside((chunkX << 4) + x, (chunkZ << 4) + z)) {
                    return true;
                }
            }
        }
        return false;
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
