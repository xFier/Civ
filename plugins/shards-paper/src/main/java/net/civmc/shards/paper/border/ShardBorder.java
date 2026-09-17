package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.civmc.shards.api.region.ShardPoint;
import net.civmc.shards.api.region.ShardRegion;
import org.bukkit.Location;

/**
 * The areas this server owns, as the proxy reported them at startup.
 *
 * <p>Held here rather than read from this server's own config so there is one copy of the shard map.
 * Two copies would disagree the moment one was edited, and a disagreement at a border is a block that
 * either belongs to nobody or to both.</p>
 */
public final class ShardBorder {

    // The four ways off a block. Edges run along an axis, so nothing diagonal can be reached without
    // first crossing one of these
    private static final int[][] STEPS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final AtomicReference<List<ShardRegion>> regions = new AtomicReference<>(List.of());

    public void set(final List<ShardRegion> regions) {
        this.regions.set(regions == null ? List.of() : List.copyOf(regions));
    }

    public boolean isConfigured() {
        return !this.regions.get().isEmpty();
    }

    /**
     * Whether a position is outside every area this server owns.
     *
     * <p>A server with no areas owns everywhere as far as this is concerned. It is not a shard - the
     * holding server is the usual case - and answering otherwise would wall its players in at the
     * first block they walked to.</p>
     */
    public boolean isOutside(final Location location) {
        final List<ShardRegion> owned = this.regions.get();
        if (owned.isEmpty()) {
            return false;
        }
        // Floored, not cast: a cast truncates towards zero, which would put someone standing at
        // x = -0.5 on block 0 instead of block -1, and so on the wrong side of a border at zero
        final int blockX = (int) Math.floor(location.getX());
        final int blockZ = (int) Math.floor(location.getZ());
        return isOutside(blockX, blockZ);
    }

    /**
     * Every face of this shard's outline within {@code radius} blocks, nearest first.
     *
     * <p>A face is one block of ours with one block of somebody else's beside it, so this describes
     * the border exactly as it really runs - round corners, along a notch where a chunk has been given
     * to a neighbour, and with a stretch that can be crossed sitting next to one that cannot. That is
     * the whole reason it is a list of faces rather than a line: a shard's outline is a rectilinear
     * polygon, and no single straight edge, and no square, can stand in for one.</p>
     *
     * <p>Costs a containment test per block in the square around the player, so it is preceded by
     * {@link #outlineWithin}, which costs a handful of comparisons and says no for everybody who is
     * not actually near a border. Callers are expected to hold on to the answer until the player
     * changes block: the outline does not move under them, only what lies beyond it does.</p>
     *
     * @param limit the most faces to return, nearest kept. A jagged outline can have a great many
     *     within sight, and drawing every one of them is a packet each
     */
    public List<EdgeSighting> facesWithin(final int blockX, final int blockZ, final int radius, final int limit) {
        if (!outlineWithin(blockX, blockZ, radius)) {
            return List.of();
        }
        final List<EdgeSighting> faces = new ArrayList<>();
        for (int x = blockX - radius; x <= blockX + radius; x++) {
            for (int z = blockZ - radius; z <= blockZ + radius; z++) {
                if (isOutside(x, z)) {
                    // Only our own blocks have faces. Asking the same question from the other side
                    // would draw the outline of ground we are not authoritative for
                    continue;
                }
                for (final int[] step : STEPS) {
                    if (isOutside(x + step[0], z + step[1])) {
                        faces.add(new EdgeSighting(
                            Math.max(Math.abs(x + step[0] - blockX), Math.abs(z + step[1] - blockZ)),
                            x + step[0], z + step[1], step[0], step[1]));
                    }
                }
            }
        }
        faces.sort(Comparator.comparingInt(EdgeSighting::distance));
        return faces.size() <= limit ? List.copyOf(faces) : List.copyOf(faces.subList(0, limit));
    }

    /**
     * Whether any of this server's areas has an edge running within {@code radius} blocks.
     *
     * <p>The cheap half of {@link #facesWithin}: it walks the corners of each area rather than the
     * blocks around the player, so it costs the same handful of comparisons wherever they stand.</p>
     *
     * <p>Measured against the outline itself rather than by looking outward along the four axes from
     * the player, which is the same mistake as drawing the border from one straight edge: a notch or
     * a corner off to one side is within sight and on none of those four lines, so a player walking
     * past one would be shown nothing at all.</p>
     *
     * <p>May say yes where the real answer is no - two of this server's own areas meeting have an
     * edge with no face on it - which costs one wasted scan and never a missing border.</p>
     */
    public boolean outlineWithin(final int blockX, final int blockZ, final int radius) {
        for (final ShardRegion region : this.regions.get()) {
            final List<ShardPoint> corners = region.vertices();
            for (int index = 0; index < corners.size(); index++) {
                if (edgeWithin(corners.get(index), corners.get((index + 1) % corners.size()),
                    blockX, blockZ, radius)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether one edge of an area passes within {@code radius} blocks.
     *
     * <p>Corners sit on the grid lines between blocks, so an edge at coordinate {@code c} separates
     * the blocks {@code c - 1} and {@code c}: the nearer of those two is what the distance is
     * measured to. Along the edge, anywhere between its ends is a distance of nothing, and past
     * either end it is the distance to the last block the edge actually runs beside - the upper end
     * exclusive, matching the rule that decides ownership.</p>
     */
    private static boolean edgeWithin(final ShardPoint from, final ShardPoint to, final int blockX,
                                      final int blockZ, final int radius) {
        final boolean runsAlongZ = from.x() == to.x();
        final int across = runsAlongZ ? from.x() : from.z();
        final int alongLow = Math.min(runsAlongZ ? from.z() : from.x(), runsAlongZ ? to.z() : to.x());
        final int alongHigh = Math.max(runsAlongZ ? from.z() : from.x(), runsAlongZ ? to.z() : to.x());
        final int blockAcross = runsAlongZ ? blockX : blockZ;
        final int blockAlong = runsAlongZ ? blockZ : blockX;

        final int distanceAcross = Math.min(Math.abs(blockAcross - across), Math.abs(blockAcross - (across - 1)));
        final int distanceAlong;
        if (blockAlong < alongLow) {
            distanceAlong = alongLow - blockAlong;
        } else if (blockAlong >= alongHigh) {
            distanceAlong = blockAlong - (alongHigh - 1);
        } else {
            distanceAlong = 0;
        }
        return Math.max(distanceAcross, distanceAlong) <= radius;
    }

    /**
     * Whether a whole chunk lies outside every area this server owns.
     *
     * <p>One block decides it. Shard edges are required to fall on chunk boundaries, so no chunk is
     * ever split between two shards and every block in it gives the same answer. Everything about the
     * mirror rests on that: it has to ask one shard for a chunk, and a chunk with two owners has no
     * one shard to ask.</p>
     */
    public boolean isChunkOutside(final int chunkX, final int chunkZ) {
        return isOutside(chunkX << 4, chunkZ << 4);
    }

    public boolean isOutside(final int blockX, final int blockZ) {
        final List<ShardRegion> owned = this.regions.get();
        if (owned.isEmpty()) {
            return false;
        }
        for (final ShardRegion region : owned) {
            if (region.containsBlock(blockX, blockZ)) {
                return false;
            }
        }
        return true;
    }
}
