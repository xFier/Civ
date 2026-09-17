package net.civmc.shards.paper.border;

/**
 * A border spotted near a player, and where it is.
 *
 * <p>The block named here is the first one <em>outside</em> this server's areas, so it is both the
 * place to ask about what lies beyond and the line to draw on: the seam runs along the near face of
 * that block.</p>
 *
 * @param distance blocks from the player to that first outside block
 * @param outsideX the outside block's x
 * @param outsideZ the outside block's z
 * @param stepX -1, 0 or 1: which way x moves to leave, so a caller knows which face of the seam this is
 * @param stepZ -1, 0 or 1: the same for z. Exactly one of the two is non-zero
 */
public record EdgeSighting(int distance, int outsideX, int outsideZ, int stepX, int stepZ) {

    public EdgeSighting {
        if ((stepX == 0) == (stepZ == 0)) {
            throw new IllegalArgumentException("An edge is crossed along exactly one axis, not " + stepX + "," + stepZ);
        }
    }

    /**
     * Where the seam itself lies on the axis being crossed.
     *
     * <p>Region corners sit on the grid lines between blocks and the upper edge is exclusive, so
     * leaving towards {@code +} means the seam is at the outside block's own coordinate, while
     * leaving towards {@code -} means it is at the coordinate one past it. Getting this the wrong way
     * round draws the line a block off, which is exactly the class of error the half-open rule exists
     * to prevent.</p>
     */
    public int seamCoordinate() {
        if (this.stepX != 0) {
            return this.stepX > 0 ? this.outsideX : this.outsideX + 1;
        }
        return this.stepZ > 0 ? this.outsideZ : this.outsideZ + 1;
    }

    public boolean alongX() {
        return this.stepX != 0;
    }

    /**
     * The block on this side of the face, which is the last one a player can stand on before it.
     */
    public int insideX() {
        return this.outsideX - this.stepX;
    }

    public int insideZ() {
        return this.outsideZ - this.stepZ;
    }

    /**
     * Whether a player standing on {@code (blockX, blockZ)} is pressed right up against this face.
     *
     * <p>The difference between telling somebody the world ends ahead and telling them it ends here,
     * which is the difference between a warning and an answer to what they just walked into.</p>
     */
    public boolean adjacentTo(final int blockX, final int blockZ) {
        return insideX() == blockX && insideZ() == blockZ;
    }
}
