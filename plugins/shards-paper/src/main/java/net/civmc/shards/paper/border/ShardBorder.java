package net.civmc.shards.paper.border;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
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
     * The closest border within {@code radius} blocks, or empty if the player is nowhere near one.
     *
     * <p>Searched along the four axis directions rather than by measuring to the region outline.
     * Every edge runs along an axis, so a border within reach is always found this way, and asking
     * {@link #containsBlock} the same question the crossing itself will ask means the answer cannot
     * disagree with it by a block - which at a seam is the whole hazard.</p>
     *
     * <p>Costs at most {@code 4 * radius} containment tests, run only when a player changes block.</p>
     */
    public Optional<EdgeSighting> nearestEdge(final int blockX, final int blockZ, final int radius) {
        if (this.regions.get().isEmpty()) {
            return Optional.empty();
        }
        EdgeSighting nearest = null;
        for (final int[] step : STEPS) {
            for (int distance = 1; distance <= radius; distance++) {
                if (nearest != null && distance >= nearest.distance()) {
                    // A closer edge was already found in another direction, so the rest of this line
                    // cannot win
                    break;
                }
                final int x = blockX + step[0] * distance;
                final int z = blockZ + step[1] * distance;
                if (isOutside(x, z)) {
                    nearest = new EdgeSighting(distance, x, z, step[0], step[1]);
                    break;
                }
            }
        }
        return Optional.ofNullable(nearest);
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
