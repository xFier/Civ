package net.civmc.shards.paper.border;

import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * How the border is shown to a player.
 *
 * <p>Two of these, chosen in config, because they fail in opposite directions. Particles are drawn
 * without anything existing in the world and cost nothing when nobody is looking, but a client set to
 * minimal particles is shown a border that is simply not there. Glass is entities, which render
 * whatever the particle setting says, at the price of being real things in a Civ world that other
 * plugins can see and count.</p>
 *
 * <p>Neither is guaranteed. The only thing that always renders is blocks actually placed in the
 * world, and this is a server whose world is player-built, so that is not on the table at any
 * price.</p>
 */
public interface BorderRenderer {

    /**
     * How far from the player faces should be looked for.
     *
     * <p>Asked rather than fixed because a renderer that keeps something in the world wants a wider
     * window than it draws in, so that walking back and forth across the edge of it does not spawn
     * and remove the same thing repeatedly.</p>
     */
    int radius();

    /**
     * The most faces worth handing over, nearest kept. A jagged outline can put a great many within
     * sight and each one costs something to draw.
     */
    int limit();

    /**
     * Brings what this player can see up to date.
     *
     * @param faces every face within {@link #radius()}, nearest first, with what lies beyond each
     *     already resolved. Faces nobody has answered about yet are not in the list
     */
    void show(Player player, Location at, List<DrawnFace> faces);

    /**
     * Takes away everything this player is being shown, at once and without ceremony.
     *
     * <p>Called when they leave or are handed over, so anything left in the world has to actually go
     * here rather than being faded out over the next few ticks - there may be no next few ticks.</p>
     */
    void forget(UUID playerUuid);

    /**
     * Called when the plugin stops. Anything this put in the world has to come back out of it.
     */
    default void close() {
    }

    /**
     * One face of the border, and whether a player may leave through it.
     *
     * @param crossable a shard owns the ground beyond and is answering. Everything else - ground
     *     owned by nobody, a neighbour that is down - is drawn the same way, because from the
     *     player's side of the line they amount to the same thing: not today
     */
    record DrawnFace(EdgeSighting face, boolean crossable) {
    }
}
