package net.civmc.shards.paper.border;

import java.util.List;
import java.util.UUID;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

/**
 * The border as coloured dust, repainted every pass.
 *
 * <p>Nothing exists between passes, so there is nothing to clean up, nothing for another plugin to
 * trip over, and nothing to pay for when a player walks away. The cost is that a client set to
 * minimal particles may be shown none of it, which is the reason {@link GlassBorderRenderer}
 * exists.</p>
 */
public final class ParticleBorderRenderer implements BorderRenderer {

    // Only what a player can see. Anything further is drawn and then not rendered, which is all cost
    // and no picture
    private static final int VISIBLE_RADIUS = 12;
    // A jagged outline - a notch, a corner, a chunk swapped to a neighbour - can put a great many
    // faces within sight, and each one is a packet. The nearest of them are the ones being looked at
    private static final int MAX_FACES = 48;
    // Four particles smeared over this much height, which reads as a curtain rather than a row of
    // dots - and is one packet per face rather than one per particle
    private static final int CURTAIN_PARTICLES = 4;
    private static final double CURTAIN_HEIGHT = 1.4D;
    private static final Particle.DustOptions CROSSABLE_DUST =
        new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.0F);
    private static final Particle.DustOptions CLOSED_DUST =
        new Particle.DustOptions(Color.fromRGB(255, 90, 90), 1.0F);

    @Override
    public int radius() {
        return VISIBLE_RADIUS;
    }

    @Override
    public int limit() {
        return MAX_FACES;
    }

    @Override
    public void show(final Player player, final Location at, final List<DrawnFace> faces) {
        final double y = Math.floor(at.getY()) + 1.0D;
        for (final DrawnFace drawn : faces) {
            final EdgeSighting face = drawn.face();
            final double seam = face.seamCoordinate();
            // The face spans its own block on the other axis, so the curtain sits across the middle
            final double x = face.alongX() ? seam : face.insideX() + 0.5D;
            final double z = face.alongX() ? face.insideZ() + 0.5D : seam;
            // Spawned at the player rather than in the world: only they are near this border, and
            // only they know whether the shard beyond it is answering
            player.spawnParticle(Particle.DUST, x, y, z, CURTAIN_PARTICLES, 0.0D, CURTAIN_HEIGHT, 0.0D,
                0.0D, drawn.crossable() ? CROSSABLE_DUST : CLOSED_DUST);
        }
    }

    @Override
    public void forget(final UUID playerUuid) {
        // Nothing is kept, so there is nothing to forget
    }
}
