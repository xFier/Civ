package net.civmc.shards.paper.border;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Showing a player where this shard ends, and whether they may leave through it, in pictures and in
 * words.
 *
 * <p>An edge that can be crossed and an edge that cannot used to look the same - which is to say,
 * like nothing at all - so the first a player knew of either was being stopped. They are drawn
 * differently now: a red curtain across a face they cannot pass, a blue one along a seam they can.</p>
 *
 * <p>Drawn face by face rather than as one line, because a shard's outline is a rectilinear polygon.
 * It turns corners, it can be notched where a chunk has been given to a neighbour, and a doorway can
 * sit directly beside a wall. A vanilla world border was tried here and cannot express any of that -
 * it is one square, so the only border it can draw truthfully is a single straight seam running to
 * the horizon, and on anything else it walls a player out of ground their own shard owns. Particles
 * cost the client-side stop that a real border gives, so walking into one is caught by the move
 * handler instead; being pushed back a block at the right place beats being held at the wrong one.</p>
 *
 * <p>Everything here is per player: the particles are spawned at them rather than in the world, so
 * two people at the same border with different neighbours reachable see different things, and nobody
 * who is nowhere near a border pays anything for it.</p>
 *
 * <p>The action bar is painted from here too, on the same pass. It is the same question, it expires,
 * and a neighbour going down changes the answer with no step taken to notice it.</p>
 */
public final class BorderView {

    // Only what a player can see. Anything further is drawn and then not rendered, which is all cost
    // and no picture
    private static final int VISIBLE_RADIUS = 12;
    // Nearer than the border is drawn from. Words about an edge eight blocks off are a warning; words
    // about every edge within sight are noise that would sit in the action bar wherever you stood
    private static final int NOTICE_RADIUS = 8;
    // A jagged outline - a notch, a corner, a chunk swapped to a neighbour - can put a great many
    // faces within sight, and each one is a packet. The nearest of them are the ones being looked at
    private static final int MAX_FACES_DRAWN = 48;
    // Roughly a 70 degree cone either side of where they are looking. Wide enough that the border you
    // are walking towards counts even when you are not staring straight at it, narrow enough that the
    // one behind you does not
    private static final double FACING_TOLERANCE = 0.34D;
    // Four particles smeared over this much height, which reads as a curtain rather than a row of
    // dots - and is one packet per face rather than one per particle
    private static final int CURTAIN_PARTICLES = 4;
    private static final double CURTAIN_HEIGHT = 1.4D;
    private static final Particle.DustOptions CROSSABLE_DUST =
        new Particle.DustOptions(Color.fromRGB(120, 200, 255), 1.0F);
    private static final Particle.DustOptions CLOSED_DUST =
        new Particle.DustOptions(Color.fromRGB(255, 90, 90), 1.0F);

    private final ShardBorder border;
    private final BorderOutlook outlook;
    private final BorderNotices notices;
    // The outline each player is standing in, kept until they change block. The outline cannot move
    // under them - only what lies beyond it can - and finding it again every pass is a containment
    // test per block in the square around them
    private final Map<UUID, Outline> outlines = new ConcurrentHashMap<>();

    public BorderView(final ShardBorder border, final BorderOutlook outlook, final BorderNotices notices) {
        this.border = border;
        this.outlook = outlook;
        this.notices = notices;
    }

    /**
     * Brings what this player can see up to date. Cheap for anyone not near a border.
     */
    public void update(final Player player) {
        final Location at = player.getLocation();
        final int blockX = at.getBlockX();
        final int blockZ = at.getBlockZ();
        final List<EdgeSighting> faces = facesAround(player, blockX, blockZ);
        if (faces.isEmpty()) {
            return;
        }
        this.outlook.refresh(faces);
        for (final EdgeSighting face : faces) {
            draw(player, at, face);
        }
        tell(player, at, blockX, blockZ, faces);
    }

    public void forget(final UUID playerUuid) {
        this.outlines.remove(playerUuid);
    }

    private List<EdgeSighting> facesAround(final Player player, final int blockX, final int blockZ) {
        final Outline cached = this.outlines.get(player.getUniqueId());
        if (cached != null && cached.blockX() == blockX && cached.blockZ() == blockZ) {
            return cached.faces();
        }
        final List<EdgeSighting> faces =
            this.border.facesWithin(blockX, blockZ, VISIBLE_RADIUS, MAX_FACES_DRAWN);
        this.outlines.put(player.getUniqueId(), new Outline(blockX, blockZ, faces));
        return faces;
    }

    /**
     * Draws one face, as a curtain standing on the seam between the two blocks.
     *
     * <p>Nothing is drawn for a face nobody has answered about yet. Colouring it either way would be
     * guessing, and the guess is visible.</p>
     */
    private void draw(final Player player, final Location at, final EdgeSighting face) {
        final BorderOutlook.Beyond beyond = this.outlook.beyond(face).orElse(null);
        if (beyond == null) {
            return;
        }
        final double seam = face.seamCoordinate();
        // The face spans its own block on the other axis, so the curtain sits across the middle of it
        final double x = face.alongX() ? seam : face.insideX() + 0.5D;
        final double z = face.alongX() ? face.insideZ() + 0.5D : seam;
        player.spawnParticle(Particle.DUST, x, Math.floor(at.getY()) + 1.0D, z, CURTAIN_PARTICLES,
            0.0D, CURTAIN_HEIGHT, 0.0D, 0.0D,
            beyond.crossable() ? CROSSABLE_DUST : CLOSED_DUST);
    }

    /**
     * Says what is beyond the border this player is walking towards.
     *
     * <p>The one they are facing, not merely the nearest one. Standing in a corner, or walking along a
     * border rather than at it, the closest face is often not the one they are about to meet, and
     * being told about a wall behind them is worse than being told nothing: it is a warning they
     * cannot act on, attached to the wrong direction.</p>
     */
    private void tell(final Player player, final Location at, final int blockX, final int blockZ,
                      final List<EdgeSighting> faces) {
        final Vector facing = at.getDirection();
        // Flattened, because a border is a thing on the ground. Taken as it comes, looking at your
        // feet would shrink every alignment below the tolerance and silence the border you are
        // standing right at
        final double facingLength = Math.hypot(facing.getX(), facing.getZ());
        if (facingLength == 0.0D) {
            return;
        }
        final double facingX = facing.getX() / facingLength;
        final double facingZ = facing.getZ() / facingLength;

        EdgeSighting ahead = null;
        double aheadAlignment = 0.0D;
        for (final EdgeSighting face : faces) {
            // Sorted nearest first, so once one is settled on, anything further cannot beat it and
            // anything past the notice radius is out of range
            if (face.distance() > NOTICE_RADIUS || (ahead != null && face.distance() > ahead.distance())) {
                break;
            }
            final double alignment = facingX * face.stepX() + facingZ * face.stepZ();
            if (alignment < FACING_TOLERANCE) {
                continue;
            }
            // Nearest wins, and among faces the same distance away, the one most squarely in front
            if (ahead == null || alignment > aheadAlignment) {
                ahead = face;
                aheadAlignment = alignment;
            }
        }
        if (ahead == null) {
            return;
        }
        this.notices.approaching(player, BorderNotices.approachMessage(
            this.outlook.beyond(ahead).orElse(null), ahead.adjacentTo(blockX, blockZ)));
    }

    /**
     * The faces around one player's block, as they were when they arrived on it.
     */
    private record Outline(int blockX, int blockZ, List<EdgeSighting> faces) {
    }
}
