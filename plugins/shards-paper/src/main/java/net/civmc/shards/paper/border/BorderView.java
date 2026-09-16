package net.civmc.shards.paper.border;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;

/**
 * Showing a player where this shard ends, and whether they may leave through it.
 *
 * <p>An edge that can be crossed and an edge that cannot used to look the same - which is to say,
 * like nothing at all - so the first a player knew of either was being stopped. They are drawn
 * differently now: a wall where crossing would be refused, a line of light where it will work.</p>
 *
 * <p>Everything here is per player. The world border is a virtual one sent only to them, and the
 * particles are spawned at them rather than in the world, so two people standing at the same border
 * with different neighbours reachable see different things - and nobody who is nowhere near a border
 * pays anything for it.</p>
 */
public final class BorderView {

    // Only what a player can see. Anything further is drawn and then not rendered, which is all cost
    // and no picture
    private static final int VISIBLE_RADIUS = 12;
    // A wall is a virtual world border, which is a square with one size. Only the face on the seam is
    // meant to be seen, so the box is made big enough that the other three are over the horizon -
    // large, but far short of the coordinate limit, so the centre never has to be clamped and the
    // face never drifts off the seam by the amount it was clamped by
    private static final double WALL_SIZE = 2_000_000.0D;
    private static final double WALL_HALF = WALL_SIZE / 2.0D;
    // Along the seam, and up, from the player
    private static final int SEAM_LENGTH = 10;
    private static final int SEAM_HEIGHT = 3;
    private static final Particle.DustOptions SEAM_DUST =
        new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.0F);

    private final ShardBorder border;
    private final BorderOutlook outlook;
    // The wall each player is currently being shown, so an unchanged one is not re-sent every pass:
    // re-sending makes the client animate the border towards its "new" size, which shimmers
    private final Map<UUID, Wall> shown = new ConcurrentHashMap<>();

    public BorderView(final ShardBorder border, final BorderOutlook outlook) {
        this.border = border;
        this.outlook = outlook;
    }

    /**
     * Brings what this player can see up to date. Cheap for anyone not near a border.
     */
    public void update(final Player player) {
        final Location at = player.getLocation();
        final Optional<EdgeSighting> nearest = this.border.nearestEdge(
            (int) Math.floor(at.getX()), (int) Math.floor(at.getZ()), VISIBLE_RADIUS);
        if (nearest.isEmpty()) {
            clear(player);
            return;
        }
        final EdgeSighting edge = nearest.get();
        final BorderOutlook.Beyond beyond = this.outlook.beyond(at, edge).orElse(null);
        if (beyond == null) {
            // Not known yet, on a first approach. Drawing a wall now and taking it away a moment later
            // would be worse than drawing nothing
            clear(player);
            return;
        }
        if (beyond.crossable()) {
            clear(player);
            drawSeam(player, at, edge);
            return;
        }
        showWall(player, edge);
    }

    /**
     * Takes away anything this player was being shown.
     *
     * <p>Called when they leave and before they are handed over, not only when they walk away from an
     * edge. A virtual world border outlives the reason it was sent, so a player carried to another
     * shard while one was up would arrive still walled in by a border belonging to the shard they
     * left.</p>
     */
    public void clear(final Player player) {
        if (this.shown.remove(player.getUniqueId()) != null) {
            // Back to the world's own border, which is what they would see if this had never run
            player.setWorldBorder(null);
        }
    }

    public void forget(final UUID playerUuid) {
        this.shown.remove(playerUuid);
    }

    private void showWall(final Player player, final EdgeSighting edge) {
        final Wall wall = new Wall(edge.alongX(), edge.seamCoordinate(),
            edge.alongX() ? edge.stepX() : edge.stepZ());
        if (wall.equals(this.shown.get(player.getUniqueId()))) {
            return;
        }
        this.shown.put(player.getUniqueId(), wall);
        player.setWorldBorder(build(wall));
    }

    /**
     * A box whose single visible face sits exactly on the seam.
     *
     * <p>The seam is a grid line, and the half-open rule means it is the outside block's own
     * coordinate when leaving towards {@code +} and one past it when leaving towards {@code -} -
     * which {@link EdgeSighting#seamCoordinate()} has already worked out. The centre is then simply
     * half a box away from it, on the inside.</p>
     */
    private static WorldBorder build(final Wall wall) {
        final WorldBorder worldBorder = Bukkit.createWorldBorder();
        worldBorder.setSize(WALL_SIZE);
        final double centre = wall.step() > 0 ? wall.seam() - WALL_HALF : wall.seam() + WALL_HALF;
        worldBorder.setCenter(wall.alongX() ? centre : 0.0D, wall.alongX() ? 0.0D : centre);
        // The wall itself is the message. The warning tint would paint the whole screen red for
        // anyone standing near their own border, which is most of a shard's edge most of the time
        worldBorder.setWarningDistance(0);
        worldBorder.setWarningTimeTicks(0);
        // Nothing here should ever hurt anybody. A player who ends up on the wrong side - put there by
        // a teleport, or by the border moving under them - is in the wrong place, not in trouble
        worldBorder.setDamageAmount(0.0D);
        worldBorder.setDamageBuffer(0.0D);
        return worldBorder;
    }

    private void drawSeam(final Player player, final Location at, final EdgeSighting edge) {
        final double seam = edge.seamCoordinate();
        final int along = (int) Math.floor(edge.alongX() ? at.getZ() : at.getX());
        final double baseY = Math.floor(at.getY());
        for (int offset = -SEAM_LENGTH; offset <= SEAM_LENGTH; offset += 2) {
            for (int height = 0; height < SEAM_HEIGHT; height++) {
                final double x = edge.alongX() ? seam : along + offset + 0.5D;
                final double z = edge.alongX() ? along + offset + 0.5D : seam;
                // Spawned at the player rather than in the world: only they are near this border, and
                // only they know whether the shard beyond it is answering
                player.spawnParticle(Particle.DUST, x, baseY + height + 0.5D, z, 1, 0.0D, 0.0D, 0.0D,
                    0.0D, SEAM_DUST);
            }
        }
    }

    /**
     * Which wall a player is being shown, so an unchanged one is left alone.
     *
     * @param alongX whether the seam is crossed in x
     * @param seam the grid line the visible face sits on
     * @param step which way leads out, so the two faces of one seam are told apart
     */
    private record Wall(boolean alongX, int seam, int step) {
    }
}
