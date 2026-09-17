package net.civmc.shards.paper.mirror;

import java.util.List;
import net.civmc.shards.api.mirror.MirroredEntity;
import org.bukkit.entity.Player;

/**
 * Drawing the frames and stands a neighbouring shard has, or not.
 *
 * <p>An interface with nothing from a packet library in it, for the same reason as
 * {@link MirrorPlayers}: an entity that is not really here can only be drawn with packets, the library
 * that sends them is optional, and a soft dependency that takes the plugin down when it is missing is
 * not soft. Nothing outside the implementation may name it.</p>
 */
public interface MirrorEntities {

    /**
     * Does nothing, for a server with no packet library. The blocks of a neighbour's build are still
     * drawn; only its contents are missing.
     */
    MirrorEntities NONE = new MirrorEntities() {
        @Override
        public void show(final Player viewer, final String world, final int chunkX, final int chunkZ,
                         final List<MirroredEntity> entities) {
        }

        @Override
        public void forget(final Player viewer, final String world, final int chunkX, final int chunkZ) {
        }

        @Override
        public void forget(final Player viewer) {
        }
    };

    /**
     * Brings one viewer's picture of one chunk's still entities up to date: draws what is new, changes
     * what has changed, and takes away what has gone.
     */
    void show(Player viewer, String world, int chunkX, int chunkZ, List<MirroredEntity> entities);

    /**
     * Takes away everything drawn for one chunk, because it has left the viewer's range.
     */
    void forget(Player viewer, String world, int chunkX, int chunkZ);

    /**
     * Forgets a viewer entirely, because they have left.
     */
    void forget(Player viewer);
}
