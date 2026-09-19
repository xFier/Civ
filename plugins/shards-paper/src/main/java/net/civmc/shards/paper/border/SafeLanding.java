package net.civmc.shards.paper.border;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * Lifting somebody who has arrived inside a block.
 *
 * <p>A crossing is aimed by the shard being left, and it aims with the only copy of the far side it
 * has: its own, which is the world as generated, without whatever the neighbour has built or dug
 * there since. So the place it picks is a place it believes is open, and the shard that actually owns
 * the ground is the only one that knows whether it is. Where it is not, the player arrives standing
 * inside something and begins to suffocate, with nothing on their screen to explain it - the blocks
 * they are drawn are the ones that are really there, and those are clear.</p>
 *
 * <p>This is the destination answering that question for itself rather than trusting the aim. It is
 * not a repair of the disagreement - the two copies still differ, and walking at a border still means
 * walking into blocks only the other server believes in - but it is the one place where the
 * disagreement costs a player health rather than smoothness.</p>
 *
 * <p>Deliberately small: it moves them straight up or straight down to the nearest place they fit, and
 * never sideways. A player who crossed a border going north expects to be slightly north, and being
 * quietly shifted along the seam to a nicer spot is worse than being lifted a block.</p>
 */
public final class SafeLanding {

    // A build over a seam is a floor and a ceiling, so the space wanted is nearly always within a
    // block or two. Past this, the far side is not a surface with something on it, it is solid - and
    // moving somebody a long way from where they aimed is its own kind of wrong
    private static final int SEARCH = 4;

    private SafeLanding() {
    }

    /**
     * Where this player can stand, nearest to where they arrived.
     *
     * <p>Main thread: it reads the world.</p>
     *
     * @return the place to put them, or null if they are already somewhere they fit - which is almost
     *     everybody, almost always
     */
    public static Location clearOf(final Player player) {
        final Location arrived = player.getLocation();
        if (fits(arrived.getWorld(), arrived.getBlockX(), arrived.getBlockY(), arrived.getBlockZ())) {
            return null;
        }
        final World world = arrived.getWorld();
        final int x = arrived.getBlockX();
        final int z = arrived.getBlockZ();
        // Up first. Ground that has been built on has gained height rather than lost it, so the open
        // space is above far more often than below, and looking down first would drop somebody through
        // a floor into a cellar they were never headed for
        for (int step = 1; step <= SEARCH; step++) {
            final int above = arrived.getBlockY() + step;
            if (above + 1 <= world.getMaxHeight() && fits(world, x, above, z)) {
                return standing(arrived, above);
            }
            final int below = arrived.getBlockY() - step;
            if (below >= world.getMinHeight() && fits(world, x, below, z)) {
                return standing(arrived, below);
            }
        }
        return null;
    }

    /**
     * Whether a player standing with their feet in this block has room for the rest of themselves.
     *
     * <p>Two blocks, because that is what a player is. Passability rather than solidity, so a crossing
     * into water, a sign or tall grass is not treated as a crossing into stone.</p>
     */
    private static boolean fits(final World world, final int x, final int y, final int z) {
        if (y < world.getMinHeight() || y + 1 > world.getMaxHeight()) {
            return false;
        }
        final Block feet = world.getBlockAt(x, y, z);
        final Block head = world.getBlockAt(x, y + 1, z);
        return feet.isPassable() && head.isPassable();
    }

    /**
     * The same spot at a new height, keeping where within the block they were and which way they face.
     */
    private static Location standing(final Location arrived, final int y) {
        final Location clear = arrived.clone();
        clear.setY(y);
        return clear;
    }
}
