package net.civmc.shards.api.mirror;

import java.util.Objects;

/**
 * One block, as the shard that owns it has it now.
 *
 * <p>Absolute coordinates rather than coordinates within a chunk, because that is what the receiving
 * side needs to send a block change and there is no saving worth the arithmetic.</p>
 *
 * @param blockData the block state as a {@code BlockData} string, the same form the chunk palette uses
 */
public record BlockUpdate(int x, int y, int z, String blockData) {

    public BlockUpdate {
        Objects.requireNonNull(blockData, "blockData");
    }
}
