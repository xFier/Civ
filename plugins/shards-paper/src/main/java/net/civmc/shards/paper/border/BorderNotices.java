package net.civmc.shards.paper.border;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.civmc.shards.api.TransferStatus;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;

/**
 * Telling a player what the border is doing, in the action bar.
 *
 * <p>Without this a border is silent in both directions: ground owned by nobody and a shard that is
 * not answering both come out as being shoved back a block with no explanation, which reads as the
 * server being broken rather than as a rule. Saying so costs nothing and turns a whole class of
 * "is it stuck?" into something a player can act on.</p>
 *
 * <p>The action bar rather than chat on purpose - this is a property of where they are standing, not
 * an event worth keeping in their log, and it disappears on its own when they walk away.</p>
 */
public final class BorderNotices {

    // An approach message repeats while they stand near the edge, so it is rate limited. Just under a
    // second, which is shorter than the action bar's own fade, so the text holds steady rather than
    // flickering as it expires and is resent
    private static final long APPROACH_INTERVAL_NANOS = TimeUnit.MILLISECONDS.toNanos(900L);

    private static final Component APPROACHING =
        Component.text("You are approaching the edge of this shard", NamedTextColor.GRAY);
    private static final Component EDGE_OF_THE_WORLD =
        Component.text("The world ends ahead", NamedTextColor.GRAY);
    private static final Component BEYOND_IS_CLOSED =
        Component.text("The land ahead is not reachable right now", NamedTextColor.RED);
    private static final Component NOWHERE_BEYOND =
        Component.text("The world ends here", NamedTextColor.GRAY);
    private static final Component NOT_ANSWERING =
        Component.text("The land beyond this border is not reachable right now", NamedTextColor.RED);
    private static final Component COULD_NOT_WRITE_BACK =
        Component.text("Your data could not be written back, so you cannot cross yet", NamedTextColor.RED);

    private final Map<UUID, Long> lastApproachAt = new ConcurrentHashMap<>();

    /**
     * What to say to someone walking towards an edge, given what is known to lie past it.
     *
     * <p>A null {@code beyond} is "not known yet" - the first approach to an edge, for the fraction
     * of a second before the probe answers - and falls back to saying only that an edge is ahead.
     * Guessing instead would mean sometimes telling a player the world ends when in truth the
     * neighbour had simply not answered in time.</p>
     */
    public static Component approachMessage(final BorderOutlook.Beyond beyond) {
        if (beyond == null) {
            return APPROACHING;
        }
        return switch (beyond.status()) {
            case CROSSABLE -> Component.text("Ahead lies ", NamedTextColor.GRAY)
                .append(Component.text(beyond.shardName(), NamedTextColor.WHITE));
            case UNOWNED -> EDGE_OF_THE_WORLD;
            case UNREACHABLE, ERROR -> BEYOND_IS_CLOSED;
        };
    }

    /**
     * Said while a player is walking towards a border, repeatedly but not every step.
     */
    public void approaching(final Player player, final Component detail) {
        final long now = System.nanoTime();
        final Long previous = this.lastApproachAt.get(player.getUniqueId());
        if (previous != null && now - previous < APPROACH_INTERVAL_NANOS) {
            return;
        }
        this.lastApproachAt.put(player.getUniqueId(), now);
        player.sendActionBar(detail == null ? APPROACHING : detail);
    }

    /**
     * Said when a crossing was refused. Never rate limited: it answers something the player just did,
     * and swallowing it would leave exactly the silence this class exists to remove.
     */
    public void refused(final Player player, final TransferStatus status) {
        player.sendActionBar(switch (status) {
            case NO_DESTINATION -> NOWHERE_BEYOND;
            case DESTINATION_UNAVAILABLE -> NOT_ANSWERING;
            case SAVE_REFUSED -> COULD_NOT_WRITE_BACK;
            default -> NOT_ANSWERING;
        });
        // So the approach message is not held back by its rate limit straight after this one, and the
        // player is not left looking at a refusal that has already stopped being true
        this.lastApproachAt.remove(player.getUniqueId());
    }

    public void forget(final UUID playerUuid) {
        this.lastApproachAt.remove(playerUuid);
    }
}
