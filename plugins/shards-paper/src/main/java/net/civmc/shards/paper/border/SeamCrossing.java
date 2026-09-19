package net.civmc.shards.paper.border;

import org.bukkit.Location;

/**
 * Where a player has reached the line with their body, rather than where they have landed with their
 * feet.
 *
 * <p>A crossing used to begin when a player's feet block became one of the neighbour's, which meant
 * standing on ground this server is not authoritative for, if only for the tick before the handover.
 * That is ground this server has its own copy of, and its own copy is the world as it generated -
 * without whatever the neighbour has since built, dug out or flattened there. The mirror draws the
 * neighbour's real blocks for the client and deliberately writes none of them here, so at every
 * column where the two differ the client and the server are simulating against different blocks.</p>
 *
 * <p>Ordinarily that is a correction and a stutter. At a border it decides whether a player can leave
 * at all: where this server's untouched copy has a hill the neighbour has long since levelled, the
 * feet block past the seam cannot be stood on, the move that would have started a handover never
 * happens, and walking into the border does nothing at all - no message, no shove, nothing in the
 * log, and stepping aside and trying again works because the next column along happens to agree.</p>
 *
 * <p>So the crossing is taken from the <strong>last block inside</strong> and the direction they are
 * travelling, with no distance to the seam anywhere in it. Standing on the last block of our own
 * ground and pressing towards the neighbour is the whole test.</p>
 *
 * <p>Measuring the distance was tried first and does not work, for a reason worth keeping: a player
 * walking into that stale wall is <em>in a fight with their own client</em>, which is predicting
 * against the neighbour's real blocks and walking forward while the server holds them against blocks
 * only it can see. Their position on this side never settles flush against anything - it oscillates
 * most of a block back - so a trigger waiting for them to come within a body's width of the seam
 * waits forever. Measured on the rig: held at z 63.0 to 63.2 against a wall at z 64, never once
 * reaching the 63.7 the old rule needed.</p>
 */
final class SeamCrossing {

    // How far over the line they land. Small, because every block of it is a block the player did not
    // walk, and big enough that no rounding of their position puts them back on the block they left
    private static final double MARGIN = 0.05D;

    private SeamCrossing() {
    }

    /**
     * The place past the border this move is heading into, or null for a move that stays within it.
     *
     * <p>Only towards a seam, never merely beside one, and only the way they are actually travelling
     * fastest - so walking <em>along</em> a border does not send somebody through the wall beside them,
     * and standing still on the last block sends them nowhere at all.</p>
     *
     * <p>Runs for every move packet rather than only for one that changes block, because the whole
     * point is that the block never has to change: the block they would be changing to is the one this
     * server cannot let them stand on. It costs a containment test or two against a handful of areas,
     * and answers no immediately for anybody who has not moved in x or z.</p>
     *
     * @return where they should arrive: the middle of the neighbour's block, at the height and facing
     *     they already had
     */
    static Location reached(final ShardBorder border, final Location from, final Location to) {
        final double movedX = to.getX() - from.getX();
        final double movedZ = to.getZ() - from.getZ();
        if (movedX == 0.0D && movedZ == 0.0D) {
            return null;
        }
        final int blockX = to.getBlockX();
        final int blockZ = to.getBlockZ();
        final int towardsX = blockX + (int) Math.signum(movedX);
        final int towardsZ = blockZ + (int) Math.signum(movedZ);

        final boolean throughX = movedX != 0.0D && border.isOutside(towardsX, blockZ);
        final boolean throughZ = movedZ != 0.0D && border.isOutside(blockX, towardsZ);
        // In a corner both are true and only one of them is the way they are going. The faster of the
        // two is that one; taken the other way, somebody walking the length of a border would be put
        // through the wall they are walking beside
        if (throughX && (!throughZ || Math.abs(movedX) >= Math.abs(movedZ))) {
            return arrivingAt(to, justPastTheSeam(towardsX, movedX), to.getZ());
        }
        if (throughZ) {
            return arrivingAt(to, to.getX(), justPastTheSeam(towardsZ, movedZ));
        }
        return null;
    }

    /**
     * Just over the line, rather than in the middle of the block over the line.
     *
     * <p>The crossing fires from anywhere on the last block inside, so the middle of the next block
     * can be most of two blocks ahead of where the player actually is. They were then <em>put</em>
     * there and handed their momentum on top, which reads in the game as being fired across the
     * border rather than walking over it - the user's words, having ridden it: it felt like travelling
     * much further, and not like one continuous movement.</p>
     *
     * <p>So they arrive as near to where they already were as the far side allows: over the line by a
     * margin, keeping the coordinate they are not crossing on and the height they were at. The margin
     * exists only so that flooring their position cannot land them back on the block they came from,
     * which would be an immediate crossing the other way.</p>
     *
     * <p>Landing this close to the seam was the thing the middle was chosen to avoid, back when
     * crossing was decided by distance to the line - a player who arrived within a body's width of it
     * was one step from being handed straight back. The rule is direction now, so what decides it is
     * which way they are walking, and somebody walking away from the seam they just crossed is not
     * near it in any sense that matters.</p>
     */
    private static double justPastTheSeam(final int outsideBlock, final double moved) {
        return moved > 0.0D ? outsideBlock + MARGIN : outsideBlock + 1.0D - MARGIN;
    }

    private static Location arrivingAt(final Location to, final double x, final double z) {
        final Location arriving = to.clone();
        arriving.setX(x);
        arriving.setZ(z);
        return arriving;
    }
}
