package net.civmc.shards.paper.border;

import java.time.Duration;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.entity.Player;

/**
 * What a player sees when they cross into this shard.
 *
 * <p>Crossing a border makes the client leave play, rebuild its registries and load terrain again -
 * the same sequence as logging in, and it looks like it. The world blinks out and comes back, and
 * without something to mark the moment it reads as having been disconnected rather than as having
 * walked somewhere. A title cannot shorten that pause, which is Minecraft's own configuration phase
 * and about 370ms of it is unavoidable; it can make it look intended.</p>
 *
 * <p>A true fade to black is not available here: nothing in the public API paints the screen, and
 * reaching for NMS to do it would give up the premise that this plugin never needs it. The title
 * fading in over the moment the world reappears is the closest thing that does not cost that.</p>
 */
public final class ArrivalCue {

    // Fade in over half a second so it arrives with the terrain rather than before it, hold briefly,
    // and leave slowly enough that it is not a flicker
    private static final Title.Times TIMES = Title.Times.times(
        Duration.ofMillis(500L), Duration.ofMillis(1500L), Duration.ofMillis(1000L));

    private final Title title;

    /**
     * @param titleText MiniMessage, blank for none
     * @param subtitleText MiniMessage, blank for none
     */
    public ArrivalCue(final String titleText, final String subtitleText) {
        if (isBlank(titleText) && isBlank(subtitleText)) {
            // Nothing sensible to greet an arrival with by default: what a shard is called to a player
            // is a decision for whoever runs it, and a server name like "shard-north" is not it
            this.title = null;
            return;
        }
        this.title = Title.title(parse(titleText), parse(subtitleText), TIMES);
    }

    public boolean isConfigured() {
        return this.title != null;
    }

    public void show(final Player player) {
        if (this.title != null) {
            player.showTitle(this.title);
        }
    }

    private static Component parse(final String text) {
        return isBlank(text) ? Component.empty() : MiniMessage.miniMessage().deserialize(text);
    }

    private static boolean isBlank(final String text) {
        return text == null || text.isBlank();
    }
}
