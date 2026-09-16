package net.civmc.shards.api.region;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * An area of the x/z plane owned by one shard, given as the corners of a polygon in order. The ring
 * closes on its own, so the config does not repeat the first corner.
 *
 * <p>Edges must run along an axis, so corners alternate between moving in x and moving in z. A
 * border that looks diagonal is written as the steps it really is. That costs nothing in practice:
 * blocks are axis-aligned, so a diagonal edge only ever describes a staircase anyway, and writing
 * the steps out makes the boundary reviewable rather than something that falls out of the
 * arithmetic. It also keeps ownership decidable in whole numbers, and lets two shards claiming the
 * same ground be caught exactly at startup.</p>
 *
 * <p>Shapes are otherwise unrestricted: concave outlines are fine, and a shard made of disjoint
 * pieces is given several regions.</p>
 *
 * <p>Ownership is per block, and two rules make which shard owns a given block unambiguous:</p>
 *
 * <ol>
 *     <li>A position is floored to a block before matching, so everywhere a player can stand within
 *     one block belongs to the same shard.</li>
 *     <li>Corners sit on the grid lines between blocks, and the upper edge is exclusive. A region
 *     with corners {@code (0,0) (100,0) (100,100) (0,100)} owns blocks {@code 0..99} on both axes.
 *     A neighbouring shard whose region starts at {@code x = 100} therefore owns {@code 100..199}:
 *     the two meet with no gap and no block owned by both, and both configs name the same
 *     {@code 100} rather than one of them needing a {@code +1}.</li>
 * </ol>
 *
 * <p>Shards are not required to touch. Blocks owned by no shard are a valid configuration, not a
 * mistake.</p>
 */
@ConfigSerializable
public record ShardRegion(List<ShardPoint> vertices) {

    public ShardRegion {
        // A ring alternating between x and z moves takes at least the four corners of a rectangle,
        // and always an even number of them
        if (vertices == null || vertices.size() < 4) {
            throw new IllegalArgumentException("A shard region needs at least 4 vertices");
        }
        if (vertices.size() % 2 != 0) {
            throw new IllegalArgumentException(
                "A shard region needs an even number of vertices, since its edges alternate between x and z");
        }
        vertices = List.copyOf(vertices);
        requireRectilinear(vertices);
    }

    /**
     * Whether this region owns the given block.
     *
     * @param blockX block coordinate, i.e. already floored
     * @param blockZ block coordinate, i.e. already floored
     */
    public boolean containsBlock(final int blockX, final int blockZ) {
        // Even-odd ray casting from the block's lower corner towards +x. Only edges running in z can
        // be crossed, and those are vertical, so the crossing sits at the edge's own x and the whole
        // test stays in whole numbers. Which endpoint of an edge counts as crossed, and the strict
        // less-than, are together what make the upper edges exclusive, so a block on a shared border
        // falls to exactly one of the two shards.
        boolean inside = false;
        final int vertexCount = this.vertices.size();
        for (int index = 0, previousIndex = vertexCount - 1; index < vertexCount; previousIndex = index++) {
            final ShardPoint corner = this.vertices.get(index);
            final ShardPoint previousCorner = this.vertices.get(previousIndex);
            if ((corner.z() > blockZ) == (previousCorner.z() > blockZ)) {
                continue;
            }
            if (blockX < corner.x()) {
                inside = !inside;
            }
        }
        return inside;
    }

    /**
     * Whether the two regions own any block in common.
     *
     * <p>Exact, not an approximation. The two regions' own coordinates cut the plane into cells, and
     * because every edge runs along an axis, no edge passes through the inside of a cell — so both
     * regions answer {@link #containsBlock} the same way everywhere within one. Testing each cell's
     * lower corner therefore settles it.</p>
     */
    public boolean overlaps(final ShardRegion other) {
        final BoundingBox shared = boundingBox().intersection(other.boundingBox());
        if (shared.isEmpty()) {
            return false;
        }
        for (final int blockX : cutCoordinates(other, true, shared.minX(), shared.maxX())) {
            for (final int blockZ : cutCoordinates(other, false, shared.minZ(), shared.maxZ())) {
                if (containsBlock(blockX, blockZ) && other.containsBlock(blockX, blockZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The smallest box containing this region.
     */
    public BoundingBox boundingBox() {
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (final ShardPoint vertex : this.vertices) {
            minX = Math.min(minX, vertex.x());
            minZ = Math.min(minZ, vertex.z());
            maxX = Math.max(maxX, vertex.x());
            maxZ = Math.max(maxZ, vertex.z());
        }
        return new BoundingBox(minX, minZ, maxX, maxZ);
    }

    /**
     * Where both regions' coordinates cut the range {@code [lowBound, highBound)}, plus {@code lowBound} itself:
     * one representative block coordinate per cell.
     */
    private List<Integer> cutCoordinates(final ShardRegion other, final boolean alongX, final int lowBound,
                                         final int highBound) {
        // lowBound is always a cut, since a region may simply cover the whole of the shared box
        final SortedSet<Integer> cuts = new TreeSet<>(List.of(lowBound));
        addCutsWithin(this.vertices, alongX, lowBound, highBound, cuts);
        addCutsWithin(other.vertices, alongX, lowBound, highBound, cuts);
        return List.copyOf(cuts);
    }

    private static void addCutsWithin(final List<ShardPoint> vertices, final boolean alongX, final int lowBound,
                                      final int highBound, final SortedSet<Integer> cuts) {
        for (final ShardPoint vertex : vertices) {
            final int coordinate = alongX ? vertex.x() : vertex.z();
            if (coordinate > lowBound && coordinate < highBound) {
                cuts.add(coordinate);
            }
        }
    }

    private static void requireRectilinear(final List<ShardPoint> vertices) {
        final int vertexCount = vertices.size();
        for (int index = 0; index < vertexCount; index++) {
            final ShardPoint corner = vertices.get(index);
            final ShardPoint nextCorner = vertices.get((index + 1) % vertexCount);
            final boolean movesX = corner.x() != nextCorner.x();
            final boolean movesZ = corner.z() != nextCorner.z();
            if (movesX && movesZ) {
                throw new IllegalArgumentException("Shard region edges must run along an axis, but "
                    + corner + " to " + nextCorner + " is diagonal");
            }
            if (!movesX && !movesZ) {
                throw new IllegalArgumentException("Shard region has a repeated vertex at " + corner);
            }
            // Two edges in a row along the same axis is a corner that does not turn, which is
            // normally a mistyped coordinate and would break the even-vertex-count rule above
            final ShardPoint cornerAfterNext = vertices.get((index + 2) % vertexCount);
            if (movesX == (nextCorner.x() != cornerAfterNext.x())) {
                throw new IllegalArgumentException("Shard region edges must alternate between x and z, but "
                    + corner + ", " + nextCorner + " and " + cornerAfterNext + " continue along the same axis");
            }
        }
    }

    public record BoundingBox(int minX, int minZ, int maxX, int maxZ) {

        /**
         * Whether the two boxes cover a block in common. The bounds are compared half-open to match
         * {@link ShardRegion#containsBlock}, so two boxes that merely share a border do not count.
         */
        public boolean overlaps(final BoundingBox other) {
            return !intersection(other).isEmpty();
        }

        public BoundingBox intersection(final BoundingBox other) {
            return new BoundingBox(
                Math.max(this.minX, other.minX), Math.max(this.minZ, other.minZ),
                Math.min(this.maxX, other.maxX), Math.min(this.maxZ, other.maxZ));
        }

        public boolean isEmpty() {
            return this.minX >= this.maxX || this.minZ >= this.maxZ;
        }
    }
}
