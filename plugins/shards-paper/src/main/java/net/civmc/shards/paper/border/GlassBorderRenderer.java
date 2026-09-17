package net.civmc.shards.paper.border;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

/**
 * The border as panes of glass, one per face.
 *
 * <p>Here because particles are not guaranteed to be drawn at all: a client set to minimal particles
 * is shown an empty field where the border is. Displays are entities, so they render whatever that
 * setting says. They are not immune either - entity render distance is still a client setting, and a
 * display's view range multiplies against it rather than overriding it - but the window here is
 * sixteen blocks, which is short enough that a generous view range clears even the lowest setting.</p>
 *
 * <p>Glass rather than a text display's coloured background. A text display would give arbitrary
 * colour at the cost of sizing everything through text metrics; the border has two colours, and
 * stained glass has both of them. Slats also have real depth and take real light, which reads as a
 * thing standing in the world rather than a decal painted on it.</p>
 *
 * <p>Everything is per player, which is what makes this usable at all: displays are real entities and
 * would otherwise be shown to everybody, including people on the far side who are not being told
 * anything. They are spawned invisible to all and revealed to the one player they belong to.</p>
 */
public final class GlassBorderRenderer implements BorderRenderer {

    // Drawn out to here, kept out to the wider radius below. Two radii rather than one so that
    // standing on the boundary and shifting about does not spawn and remove the same pane repeatedly.
    // Four blocks of hysteresis is about three quarters of a second at a sprint, comfortably longer
    // than the fade below, so even somebody deliberately walking the boundary sees panes finish
    // appearing rather than flicker
    private static final int SPAWN_RADIUS = 12;
    private static final int KEEP_RADIUS = 16;
    private static final int MAX_FACES = 48;
    // Beyond this, every other pane along a run is left out. Halves the count on a long straight
    // stretch, where the gap is not the detail anybody is looking at
    private static final int SOLID_WITHIN = 8;

    private static final float THICKNESS = 0.08F;
    private static final float HEIGHT = 2.5F;
    // A pane stands on the ground under its own face where the ground is near the player, and at the
    // player's own level where it is not - underground, or flying. Sampled once, when the face comes
    // into the window
    private static final int GROUND_SEARCH = 4;
    // Entity render distance is a client setting that this multiplies against, so it is set well past
    // the window rather than trimmed to it: the saving is not worth a border that fades out at ten
    // blocks for anybody playing on a low setting
    private static final float VIEW_RANGE = 1.0F;
    // Fully lit, so a border is the same colour at midnight and down a hole as it is at noon
    private static final Display.Brightness FULLY_LIT = new Display.Brightness(15, 15);
    // Long enough to read as growing rather than blinking, short enough to finish inside one pass of
    // the timer that drives this
    private static final int FADE_TICKS = 4;

    private static final BlockData CROSSABLE_GLASS = Material.LIGHT_BLUE_STAINED_GLASS.createBlockData();
    private static final BlockData CLOSED_GLASS = Material.RED_STAINED_GLASS.createBlockData();

    private final JavaPlugin plugin;
    private final Map<UUID, Map<Pane, Standing>> shown = new HashMap<>();
    // Every pane this has ever put in the world and not yet taken out, including the ones part way
    // through fading. A display that outlives the plugin is litter nothing else will ever clean up
    private final Set<BlockDisplay> live = new HashSet<>();

    public GlassBorderRenderer(final JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public int radius() {
        return KEEP_RADIUS;
    }

    @Override
    public int limit() {
        return MAX_FACES;
    }

    @Override
    public void show(final Player player, final Location at, final List<DrawnFace> faces) {
        if (faces.isEmpty() && !this.shown.containsKey(player.getUniqueId())) {
            // Which is almost everybody, almost all the time
            return;
        }
        final Map<Pane, Boolean> wanted = wanted(faces);
        final Map<Pane, Standing> standing =
            this.shown.computeIfAbsent(player.getUniqueId(), uuid -> new HashMap<>());

        for (final Iterator<Map.Entry<Pane, Standing>> entries = standing.entrySet().iterator();
             entries.hasNext(); ) {
            final Map.Entry<Pane, Standing> entry = entries.next();
            final Boolean stillWanted = wanted.get(entry.getKey());
            final BlockDisplay display = entry.getValue().display();
            if (stillWanted == null || !display.isValid() || !display.getWorld().equals(at.getWorld())) {
                entries.remove();
                fadeOut(entry.getKey(), display);
                continue;
            }
            if (stillWanted != entry.getValue().crossable()) {
                // A pane that has only changed colour is recoloured where it stands. Replacing it
                // would mean a despawn and a respawn at every face at once whenever a neighbour goes
                // down, which is the moment a player most wants the border to stay put
                display.setBlock(stillWanted ? CROSSABLE_GLASS : CLOSED_GLASS);
                entry.setValue(new Standing(display, stillWanted));
            }
        }

        final List<Map.Entry<Pane, BlockDisplay>> grown = new ArrayList<>();
        for (final Map.Entry<Pane, Boolean> entry : wanted.entrySet()) {
            if (standing.containsKey(entry.getKey())) {
                continue;
            }
            final BlockDisplay display =
                raise(player, at.getWorld(), at.getBlockY(), entry.getKey(), entry.getValue());
            standing.put(entry.getKey(), new Standing(display, entry.getValue()));
            grown.add(Map.entry(entry.getKey(), display));
        }
        if (standing.isEmpty()) {
            // Nothing left near this player, so their row goes too rather than sitting empty for as
            // long as they stay online
            this.shown.remove(player.getUniqueId());
        }
        if (!grown.isEmpty()) {
            // A tick later, so the client has been told the pane exists at no size before it is told
            // what size to become. Both in one tick and it simply appears at full size
            Bukkit.getScheduler().runTask(this.plugin,
                () -> grown.forEach(pane -> growIn(pane.getKey(), pane.getValue())));
        }
    }

    /**
     * Which panes should be standing, and what colour.
     *
     * <p>Only faces inside the spawn radius are wanted, while everything out to the keep radius is
     * left alone by the caller - that gap is the hysteresis. Past {@link #SOLID_WITHIN} every other
     * pane along a run is dropped, except at the ends of runs: those are the corners, and thinning
     * that erased a corner would erase the shape this exists to show.</p>
     */
    private static Map<Pane, Boolean> wanted(final List<DrawnFace> faces) {
        final Set<Pane> present = new HashSet<>();
        for (final DrawnFace drawn : faces) {
            present.add(Pane.of(drawn.face()));
        }
        final Map<Pane, Boolean> wanted = new HashMap<>();
        for (final DrawnFace drawn : faces) {
            final EdgeSighting face = drawn.face();
            if (face.distance() > SPAWN_RADIUS) {
                // Sorted nearest first, so everything past here is further still
                break;
            }
            final Pane pane = Pane.of(face);
            if (face.distance() > SOLID_WITHIN && !pane.turnsAt(present) && pane.along() % 2 != 0) {
                continue;
            }
            wanted.put(pane, drawn.crossable());
        }
        return wanted;
    }

    private BlockDisplay raise(final Player player, final World world, final int playerY, final Pane pane,
                               final boolean crossable) {
        final Location standing = pane.standsAt(world, playerY);
        final BlockDisplay display = world.spawn(standing, BlockDisplay.class, spawned -> {
            // Before it is added to the world, so it is never briefly visible to everybody nearby
            spawned.setVisibleByDefault(false);
            // Or a crash writes the whole border into the region file, to be found by whoever loads
            // that chunk next
            spawned.setPersistent(false);
            spawned.setBlock(crossable ? CROSSABLE_GLASS : CLOSED_GLASS);
            spawned.setBrightness(FULLY_LIT);
            spawned.setViewRange(VIEW_RANGE);
            // It has depth and sits on a line, so it must stay where the line is rather than turning
            // to face whoever is looking at it
            spawned.setBillboard(Display.Billboard.FIXED);
            // Otherwise every pane puts a dark blot on the ground beneath it and a border reads as a
            // stain rather than a wall
            spawned.setShadowRadius(0.0F);
            spawned.setInterpolationDuration(FADE_TICKS);
            spawned.setTransformation(pane.shape(0.0F));
        });
        player.showEntity(this.plugin, display);
        this.live.add(display);
        return display;
    }

    private void growIn(final Pane pane, final BlockDisplay display) {
        if (!display.isValid()) {
            return;
        }
        display.setInterpolationDelay(0);
        display.setTransformation(pane.shape(HEIGHT));
    }

    private void fadeOut(final Pane pane, final BlockDisplay display) {
        if (!display.isValid()) {
            this.live.remove(display);
            return;
        }
        display.setInterpolationDelay(0);
        display.setTransformation(pane.shape(0.0F));
        Bukkit.getScheduler().runTaskLater(this.plugin, () -> {
            display.remove();
            this.live.remove(display);
        }, FADE_TICKS);
    }

    /**
     * Takes this player's panes away at once, with no fade.
     *
     * <p>Called when they are handed over or disconnect, and in both cases there may be no next tick
     * for them - a pane left mid-fade would be an invisible entity nobody ever removes.</p>
     */
    @Override
    public void forget(final UUID playerUuid) {
        final Map<Pane, Standing> standing = this.shown.remove(playerUuid);
        if (standing == null) {
            return;
        }
        for (final Standing pane : standing.values()) {
            pane.display().remove();
            this.live.remove(pane.display());
        }
    }

    @Override
    public void close() {
        for (final BlockDisplay display : this.live) {
            display.remove();
        }
        this.live.clear();
        this.shown.clear();
    }

    /**
     * A pane in the world, and the colour it was last given. Kept so an unchanged one is left alone:
     * setting the block every pass would be a packet per pane twice a second saying nothing new.
     */
    private record Standing(BlockDisplay display, boolean crossable) {
    }

    /**
     * One pane, named by the face it stands on.
     *
     * <p>The step is part of its identity, not decoration: the two sides of one seam are two faces,
     * and a shard can own the ground on either side of a line somewhere else along it.</p>
     *
     * @param insideX the block on our side of the face
     * @param seam the grid line the pane stands on
     * @param alongX whether the seam is crossed in x, so the pane runs in z
     */
    private record Pane(int insideX, int insideZ, int seam, boolean alongX) {

        static Pane of(final EdgeSighting face) {
            return new Pane(face.insideX(), face.insideZ(), face.seamCoordinate(), face.alongX());
        }

        /**
         * Where along its own run this pane sits, for thinning. Taken from the coordinate that varies
         * along the run, so the panes that survive are the same ones from pass to pass - thinning on
         * anything that moved with the player would make the gaps crawl.
         */
        int along() {
            return Math.floorMod(this.alongX ? this.insideZ : this.insideX, 2);
        }

        /**
         * Whether the run this pane belongs to ends beside it, which is to say whether it is at a
         * corner.
         */
        boolean turnsAt(final Set<Pane> present) {
            return !present.contains(shifted(-1)) || !present.contains(shifted(1));
        }

        private Pane shifted(final int by) {
            return this.alongX
                ? new Pane(this.insideX, this.insideZ + by, this.seam, true)
                : new Pane(this.insideX + by, this.insideZ, this.seam, false);
        }

        /**
         * Where the pane stands, and how tall the ground lets it be.
         *
         * <p>On the surface under its own face when that is near the player, and at the player's own
         * level when it is not. A border underground would otherwise be drawn on the hillside above
         * it, and one in the open would be a ribbon hanging at whatever height the player happened to
         * be when it appeared.</p>
         */
        Location standsAt(final World world, final int playerY) {
            final int surface = world.getHighestBlockYAt(this.insideX, this.insideZ) + 1;
            final int base = Math.max(playerY - GROUND_SEARCH, Math.min(playerY + GROUND_SEARCH, surface));
            // A block display draws its block from its own position outwards, so the pane is placed at
            // the low corner of the volume it should fill rather than at the middle of it
            return this.alongX
                ? new Location(world, this.seam - THICKNESS / 2.0F, base, this.insideZ)
                : new Location(world, this.insideX, base, this.seam - THICKNESS / 2.0F);
        }

        Transformation shape(final float height) {
            final Vector3f scale = this.alongX
                ? new Vector3f(THICKNESS, height, 1.0F)
                : new Vector3f(1.0F, height, THICKNESS);
            return new Transformation(new Vector3f(), new AxisAngle4f(), scale, new AxisAngle4f());
        }
    }
}
