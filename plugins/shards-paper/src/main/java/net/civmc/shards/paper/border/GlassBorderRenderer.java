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
import org.bukkit.Color;
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
 * stained glass has both of them. Panes also have real depth and take real light, which reads as a
 * thing standing in the world rather than a decal painted on it.</p>
 *
 * <p>Each pane stands in the open space the player is in, found by looking down from their own level
 * rather than down from the sky. That is what puts the border on the lake bed instead of the lake, on
 * the cave floor instead of the hillside overhead, and on the ground under a tree instead of in its
 * canopy.</p>
 *
 * <p>They glow, so the outline is drawn through anything in front of them. Glass inside rock is
 * invisible, and a border is worst as a surprise: digging towards one, running a tunnel beside one,
 * or standing in a building built over one are exactly the places where the first you would otherwise
 * know of it is being stopped.</p>
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
    // The most panes one player is shown, spent on the nearest faces. Faces are handed over well past
    // this so that the ones inside the spawn radius are never crowded out by ones in the keep band
    // that are not going to be drawn at all
    private static final int MAX_PANES = 48;
    private static final int HANDED_OVER = 160;

    private static final float THICKNESS = 0.08F;
    // Tall enough to read as a wall, and it starts a block below the floor rather than on it. That
    // buried block is the point: neighbouring faces on a slope stand at different heights, and
    // without the overlap the border comes apart into a staircase with a gap at every step
    private static final float HEIGHT = 4.0F;
    private static final int SUNK = 1;
    // How far above the player a pane will look for open space before giving up on the face, and how
    // far below it will follow that space down to a floor
    private static final int OPEN_ABOVE = 4;
    private static final int FLOOR_BELOW = 8;
    // A pane stands on the floor while the player is within this much of it. Higher than that - up a
    // pillar, or flying - the floor is not where they are looking, so the pane comes up to meet them,
    // in steps of FOLLOW_STEP so that climbing does not re-place every pane on every block
    private static final int FOLLOW_ABOVE = 3;
    private static final int FOLLOW_STEP = 3;
    // What the client uses to decide the pane is off screen. Left at the entity's own size, a pane
    // scaled well past it is culled while plainly in view
    private static final float CULLING_WIDTH = 2.0F;
    private static final float CULLING_HEIGHT = HEIGHT + 2.0F;
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
    // The outline colours, matched to the glass so a pane seen through rock and the same pane seen in
    // the open are recognisably the one thing
    private static final Color CROSSABLE_GLOW = Color.fromRGB(120, 200, 255);
    private static final Color CLOSED_GLOW = Color.fromRGB(255, 90, 90);

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
        return HANDED_OVER;
    }

    @Override
    public void show(final Player player, final Location at, final List<DrawnFace> faces) {
        if (faces.isEmpty() && !this.shown.containsKey(player.getUniqueId())) {
            // Which is almost everybody, almost all the time
            return;
        }
        final Map<Pane, Boolean> wanted = wanted(at.getWorld(), at.getBlockY(), faces);
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
                display.setGlowColorOverride(stillWanted ? CROSSABLE_GLOW : CLOSED_GLOW);
                entry.setValue(new Standing(display, stillWanted));
            }
        }

        final List<Map.Entry<Pane, BlockDisplay>> grown = new ArrayList<>();
        for (final Map.Entry<Pane, Boolean> entry : wanted.entrySet()) {
            if (standing.containsKey(entry.getKey())) {
                continue;
            }
            final BlockDisplay display = raise(player, at.getWorld(), entry.getKey(), entry.getValue());
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
     * Which panes should be standing, where, and what colour.
     *
     * <p>Only faces inside the spawn radius are wanted, while everything out to the keep radius is
     * left standing by the caller - that gap is the hysteresis. Every face in range gets a pane;
     * dropping every other one further out was tried and is what made the border read as a row of
     * rectangles rather than a wall.</p>
     *
     * <p>The cap is spent nearest first. The faces arrive sorted, so stopping at the limit keeps the
     * panes closest to the player, which are the ones they are looking at - a jagged outline can
     * otherwise spend the whole budget on faces off to the side.</p>
     */
    private static Map<Pane, Boolean> wanted(final World world, final int playerY,
                                             final List<DrawnFace> faces) {
        final Map<Pane, Boolean> wanted = new HashMap<>();
        for (final DrawnFace drawn : faces) {
            final EdgeSighting face = drawn.face();
            if (face.distance() > SPAWN_RADIUS || wanted.size() == MAX_PANES) {
                // Sorted nearest first, so everything past here is further still
                break;
            }
            final int base = baseUnder(world, face.insideX(), face.insideZ(), playerY);
            wanted.put(new Pane(face.insideX(), face.insideZ(), face.seamCoordinate(), face.alongX(), base),
                drawn.crossable());
        }
        return wanted;
    }

    /**
     * Where a pane at this column should stand.
     *
     * <p>Found by looking for the open space the player themselves is in and following its floor
     * down, rather than by asking for the highest block. The highest block is wrong three ways over:
     * it counts water, so a border across a lake stood on the surface and disappeared the moment you
     * swam under it; it counts leaves, so one under a tree hung at canopy height; and it looks from
     * the sky, so in a cave or a building it reported the hillside or the roof overhead and the pane
     * was placed in the ceiling. Clamping the result back towards the player then buried the panes it
     * had put too high, and left the rest hanging at whatever height the player happened to be.</p>
     *
     * <p>Water and air are both open here, so the search comes to rest on the lake bed rather than on
     * the lake, and a border crossing one is drawn where somebody swimming can see it.</p>
     *
     * <p>Where the column is solid all the way up - a border running into a hillside, or through a
     * wall somebody has built on it - the pane stands at the player's own level instead. It is inside
     * rock there and invisible as glass, which is exactly the case the glow is for.</p>
     *
     * <p>And where the floor is a long way down, because the player is up a pillar or flying, the
     * pane comes up to meet them rather than staying on the ground. A border is a thing you are about
     * to cross, so it belongs where you are about to cross it; left on the floor it hung several
     * blocks below anybody in the air, which is both useless and the wrong answer about where the
     * border is.</p>
     */
    private static int baseUnder(final World world, final int x, final int z, final int playerY) {
        final int ceiling = Math.min(world.getMaxHeight() - 1, playerY + OPEN_ABOVE);
        int open = Integer.MIN_VALUE;
        for (int y = Math.max(playerY, world.getMinHeight()); y <= ceiling; y++) {
            if (!world.getBlockAt(x, y, z).getType().isSolid()) {
                open = y;
                break;
            }
        }
        if (open == Integer.MIN_VALUE) {
            return playerY - SUNK;
        }
        final int lowest = Math.max(world.getMinHeight(), playerY - FLOOR_BELOW);
        int floor = open;
        while (floor > lowest && !world.getBlockAt(x, floor - 1, z).getType().isSolid()) {
            floor--;
        }
        return follow(floor - SUNK, playerY);
    }

    /**
     * Lifts a pane off the floor once the player is well above it.
     *
     * <p>In steps rather than continuously. Following exactly would mean every pane in sight being
     * taken down and raised again on every block of a climb, and a jump would do it too - the step is
     * what makes a pane that already covers the player be left alone.</p>
     */
    private static int follow(final int floorBase, final int playerY) {
        final int above = playerY - floorBase;
        if (above <= FOLLOW_ABOVE) {
            return floorBase;
        }
        // Snapped against the floor rather than against the player, so two players at slightly
        // different heights over the same ground are shown the pane in the same place
        return floorBase + Math.floorDiv(above, FOLLOW_STEP) * FOLLOW_STEP;
    }

    private BlockDisplay raise(final Player player, final World world, final Pane pane,
                               final boolean crossable) {
        final Location standing = pane.standsAt(world);
        final BlockDisplay display = world.spawn(standing, BlockDisplay.class, spawned -> {
            // Before it is added to the world, so it is never briefly visible to everybody nearby
            spawned.setVisibleByDefault(false);
            // Or a crash writes the whole border into the region file, to be found by whoever loads
            // that chunk next
            spawned.setPersistent(false);
            spawned.setBlock(crossable ? CROSSABLE_GLASS : CLOSED_GLASS);
            // Outlined, and the outline is drawn through whatever is in front of it. Without this a
            // border is only ever a surprise to somebody digging towards it, or walking a tunnel
            // beside it, or in a building standing on it - all the places where finding out by being
            // stopped is worst
            spawned.setGlowing(true);
            spawned.setGlowColorOverride(crossable ? CROSSABLE_GLOW : CLOSED_GLOW);
            spawned.setBrightness(FULLY_LIT);
            spawned.setViewRange(VIEW_RANGE);
            // It has depth and sits on a line, so it must stay where the line is rather than turning
            // to face whoever is looking at it
            spawned.setBillboard(Display.Billboard.FIXED);
            // Otherwise every pane puts a dark blot on the ground beneath it and a border reads as a
            // stain rather than a wall
            spawned.setShadowRadius(0.0F);
            spawned.setDisplayWidth(CULLING_WIDTH);
            spawned.setDisplayHeight(CULLING_HEIGHT);
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
     * One pane, named by the face it stands on and the height it stands at.
     *
     * <p>The seam is part of its identity, not decoration: the two sides of one line are two faces,
     * and a shard can own the ground on either side of it somewhere else along that line.</p>
     *
     * <p>So is the base. A pane whose floor has changed is a different pane and is raised again,
     * which is what lets one follow a player down into a cave or up onto a roof. In practice it
     * hardly ever changes, because the floor is found from the terrain rather than from where the
     * player happens to be standing.</p>
     *
     * @param insideX the block on our side of the face
     * @param seam the grid line the pane stands on
     * @param alongX whether the seam is crossed in x, so the pane runs in z
     * @param base the y the pane starts at, already sunk into the floor
     */
    private record Pane(int insideX, int insideZ, int seam, boolean alongX, int base) {

        Location standsAt(final World world) {
            // A block display draws its block from its own position outwards, so the pane is placed at
            // the low corner of the volume it should fill rather than at the middle of it
            return this.alongX
                ? new Location(world, this.seam - THICKNESS / 2.0F, this.base, this.insideZ)
                : new Location(world, this.insideX, this.base, this.seam - THICKNESS / 2.0F);
        }

        Transformation shape(final float height) {
            final Vector3f scale = this.alongX
                ? new Vector3f(THICKNESS, height, 1.0F)
                : new Vector3f(1.0F, height, THICKNESS);
            return new Transformation(new Vector3f(), new AxisAngle4f(), scale, new AxisAngle4f());
        }
    }
}
