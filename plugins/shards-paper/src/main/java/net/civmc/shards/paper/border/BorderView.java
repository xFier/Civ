package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Location;
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
 * the horizon, and on anything else it walls a player out of ground their own shard owns. Whatever is
 * drawn instead costs the client-side stop that a real border gives, so walking into one is caught by
 * the move handler; being pushed back a block at the right place beats being held at the wrong one.</p>
 *
 * <p>What the faces are drawn <em>with</em> is a {@link BorderRenderer}, chosen in config. This class
 * works out where the border is and what is beyond it, which is the same question however it is
 * shown.</p>
 *
 * <p>Everything here is per player, so two people at the same border with different neighbours
 * reachable see different things, and nobody who is nowhere near a border pays anything for it.</p>
 *
 * <p>The action bar is painted from here too, on the same pass. It is the same question, it expires,
 * and a neighbour going down changes the answer with no step taken to notice it.</p>
 */
public final class BorderView {

    // Nearer than the border is drawn from. Words about an edge eight blocks off are a warning; words
    // about every edge within sight are noise that would sit in the action bar wherever you stood
    private static final int NOTICE_RADIUS = 8;
    // Roughly a 70 degree cone either side of where they are looking. Wide enough that the border you
    // are walking towards counts even when you are not staring straight at it, narrow enough that the
    // one behind you does not
    private static final double FACING_TOLERANCE = 0.34D;

    private final ShardBorder border;
    private final BorderOutlook outlook;
    private final BorderNotices notices;
    private final BorderRenderer renderer;
    // The outline each player is standing in, kept until they change block. The outline cannot move
    // under them - only what lies beyond it can - and finding it again every pass is a containment
    // test per block in the square around them
    private final Map<UUID, Outline> outlines = new ConcurrentHashMap<>();

    public BorderView(final ShardBorder border, final BorderOutlook outlook, final BorderNotices notices,
                      final BorderRenderer renderer) {
        this.border = border;
        this.outlook = outlook;
        this.notices = notices;
        this.renderer = renderer;
    }

    /**
     * Brings what this player can see up to date. Cheap for anyone not near a border.
     */
    public void update(final Player player) {
        final Location at = player.getLocation();
        final int blockX = at.getBlockX();
        final int blockZ = at.getBlockZ();
        final List<EdgeSighting> faces = facesAround(player, blockX, blockZ);
        this.outlook.refresh(faces);
        // Told even when there is nothing to draw, because a renderer that keeps something in the
        // world has to hear that the player has walked away from it - or changed world, which is the
        // case where nothing here would ever mention that border again
        this.renderer.show(player, at, drawable(faces));
        if (!faces.isEmpty()) {
            tell(player, at, blockX, blockZ, faces);
        }
    }

    public void forget(final UUID playerUuid) {
        this.outlines.remove(playerUuid);
        this.renderer.forget(playerUuid);
    }

    public void close() {
        this.renderer.close();
        this.outlines.clear();
    }

    /**
     * The faces that can actually be drawn: the ones somebody has answered about.
     *
     * <p>A face nobody has answered about yet is left out rather than drawn in some third colour.
     * Colouring it either way would be guessing, and the guess is visible.</p>
     */
    private List<BorderRenderer.DrawnFace> drawable(final List<EdgeSighting> faces) {
        final List<BorderRenderer.DrawnFace> drawn = new ArrayList<>(faces.size());
        for (final EdgeSighting face : faces) {
            this.outlook.beyond(face).ifPresent(beyond ->
                drawn.add(new BorderRenderer.DrawnFace(face, beyond.crossable())));
        }
        return drawn;
    }

    private List<EdgeSighting> facesAround(final Player player, final int blockX, final int blockZ) {
        final Outline cached = this.outlines.get(player.getUniqueId());
        if (cached != null && cached.blockX() == blockX && cached.blockZ() == blockZ) {
            return cached.faces();
        }
        final List<EdgeSighting> faces =
            this.border.facesWithin(blockX, blockZ, this.renderer.radius(), this.renderer.limit());
        this.outlines.put(player.getUniqueId(), new Outline(blockX, blockZ, faces));
        return faces;
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
