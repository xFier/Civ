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
    // A refusal answers something the player just did, so the approach message is held back long
    // enough for it to be read. Without this the repaint a fraction of a second later would wipe out
    // the only explanation they were ever given
    private static final long REFUSAL_HOLD_NANOS = TimeUnit.SECONDS.toNanos(3L);

    private static final Component APPROACHING =
        Component.text("You are approaching the edge of this shard", NamedTextColor.GRAY);
    private static final Component AT_THE_EDGE =
        Component.text("You are at the edge of this shard", NamedTextColor.GRAY);
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

    // When each player may next be shown an approach message, rather than when they last were: a
    // refusal wants the next one held back further than the ordinary gap between two
    private final Map<UUID, Long> approachAllowedAt = new ConcurrentHashMap<>();

    /**
     * What to say to someone walking towards an edge, given what is known to lie past it.
     *
     * <p>A null {@code beyond} is "not known yet" - the first approach to an edge, for the fraction
     * of a second before the probe answers - and falls back to saying only that an edge is ahead.
     * Guessing instead would mean sometimes telling a player the world ends when in truth the
     * neighbour had simply not answered in time.</p>
     *
     * @param pressedAgainstIt whether they are standing on the last block before it, in which case
     *     the words stop being a warning about what is ahead and become an answer about where they
     *     already are. This is the only way the "here" wording is reached now: it used to arrive with
     *     a refused crossing, which meant it was never seen at a border that stops you before you can
     *     attempt one
     */
    public static Component approachMessage(final BorderOutlook.Beyond beyond, final boolean pressedAgainstIt) {
        if (beyond == null) {
            return pressedAgainstIt ? AT_THE_EDGE : APPROACHING;
        }
        return switch (beyond.status()) {
            case CROSSABLE -> Component.text(pressedAgainstIt ? "Across this line lies " : "Ahead lies ",
                    NamedTextColor.GRAY)
                .append(Component.text(beyond.shardName(), NamedTextColor.WHITE));
            case UNOWNED -> pressedAgainstIt ? NOWHERE_BEYOND : EDGE_OF_THE_WORLD;
            case UNREACHABLE -> pressedAgainstIt ? NOT_ANSWERING : BEYOND_IS_CLOSED;
        };
    }

    /**
     * Said while a player is walking towards a border, repeatedly but not every step.
     */
    public void approaching(final Player player, final Component detail) {
        final long now = System.nanoTime();
        final Long allowedAt = this.approachAllowedAt.get(player.getUniqueId());
        if (allowedAt != null && now - allowedAt < 0L) {
            return;
        }
        this.approachAllowedAt.put(player.getUniqueId(), now + APPROACH_INTERVAL_NANOS);
        player.sendActionBar(detail == null ? APPROACHING : detail);
    }

    /**
     * Said when a crossing was refused. Never rate limited: it answers something the player just did,
     * and swallowing it would leave exactly the silence this class exists to remove. It also holds the
     * repainted approach message back for a few seconds, so the refusal is read rather than replaced.
     */
    public void refused(final Player player, final TransferStatus status) {
        player.sendActionBar(switch (status) {
            case NO_DESTINATION -> NOWHERE_BEYOND;
            case DESTINATION_UNAVAILABLE -> NOT_ANSWERING;
            case SAVE_REFUSED -> COULD_NOT_WRITE_BACK;
            default -> NOT_ANSWERING;
        });
        this.approachAllowedAt.put(player.getUniqueId(), System.nanoTime() + REFUSAL_HOLD_NANOS);
    }

    public void forget(final UUID playerUuid) {
        this.approachAllowedAt.remove(playerUuid);
    }
}
