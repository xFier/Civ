package net.civmc.shards.paper.border;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;

/**
 * What the shard on the other side of a border really has on its ground.
 *
 * <p>An interface rather than the mirror itself, for two reasons. The mirror is built after the
 * border is, and may not be built at all - it is a config key - so what the border holds has to be
 * something that can answer "not known" for the whole life of the server. And the mirror already
 * names the border; naming it back would tie the two together in both directions for one lookup.</p>
 */
@FunctionalInterface
public interface FarSideBlocks {

    /**
     * @return what the owning shard has there, or null where this server has not been told - which is
     *     "not known" and must never be read as "nothing there"
     */
    BlockData at(World world, int x, int y, int z);
}
