package net.civmc.shards.api.mirror;

import java.util.List;

/**
 * One 16x16x16 slice of a chunk, as the shard that owns it really has it.
 *
 * <p>Stored as a palette of distinct block states plus one index per block, which is how the game
 * stores a section itself and is what makes a section of solid stone a handful of bytes rather than
 * four thousand copies of the same string.</p>
 *
 * @param palette the distinct block states in this section, as {@code BlockData} strings. Null for a
 *     section that is entirely air
 * @param indices one entry per block, in y-z-x order, indexing into {@code palette}. Null when the
 *     palette is
 */
public record ChunkSectionState(List<String> palette, short[] indices) {

    public static final int BLOCKS_PER_SECTION = 16 * 16 * 16;

    /**
     * A section with nothing in it.
     *
     * <p>Sent explicitly rather than left out. The shard receiving this has its own copy of the same
     * ground and may well have something in that section, and "the owner has air here" is exactly the
     * difference it needs to be told about - a building demolished since the copy was taken looks
     * identical to a section nobody mentioned.</p>
     */
    public static ChunkSectionState empty() {
        return new ChunkSectionState(null, null);
    }

    public boolean isEmpty() {
        return this.palette == null;
    }

    /**
     * The index into {@code indices} for a block within the section.
     */
    public static int indexOf(final int x, final int y, final int z) {
        return (y << 8) | (z << 4) | x;
    }
}
