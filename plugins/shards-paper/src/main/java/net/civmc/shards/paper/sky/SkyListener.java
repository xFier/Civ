package net.civmc.shards.paper.sky;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.NightSkipRequest;
import net.civmc.shards.api.NightSkipResponse;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.TimeSkipEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Turns sleeping through the night into something the whole network does.
 *
 * <p>A shard that skips its own night is undone by the next answer from the proxy, which still says
 * it is dark - so the players who slept would watch morning arrive and then be taken away again. The
 * local skip is cancelled and the proxy is asked to move everybody on instead.</p>
 *
 * <p>Only a night skip. A command or a plugin setting the time is somebody deliberately doing
 * something to this server, and the sync correcting it a moment later is the honest outcome - it is
 * not this shard's clock to set any more. Cancelling those here would hide that behind a silent
 * no-op.</p>
 */
public final class SkyListener implements Listener {

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final SkySync sync;
    private final String serverName;
    private final Logger logger;

    public SkyListener(final JavaPlugin plugin, final ShardsClient client, final SkySync sync,
                       final String serverName, final Logger logger) {
        this.plugin = plugin;
        this.client = client;
        this.sync = sync;
        this.serverName = serverName;
        this.logger = logger;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTimeSkip(final TimeSkipEvent event) {
        if (event.getSkipReason() != TimeSkipEvent.SkipReason.NIGHT_SKIP) {
            return;
        }
        event.setCancelled(true);
        if (!this.client.isReady()) {
            // Nothing can be asked, so nothing happens. Letting the local skip through instead would
            // be worse than doing nothing: the sync would undo it the moment the proxy came back,
            // which reads as the night being taken away rather than as sleeping not having worked
            this.logger.warning("Players slept, but the proxy cannot be reached, so the night stands");
            return;
        }
        this.client.skipNight(NightSkipRequest.create(this.serverName))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> applyAnswer(response, error)));
    }

    private void applyAnswer(final NightSkipResponse response, final Throwable error) {
        if (error != null || response == null || response.failed()) {
            this.logger.log(Level.WARNING, "Players slept, but the network's clock could not be moved on", error);
            return;
        }
        // Applied whether or not this request is the one that did it. Another shard sleeping in the
        // same second is still morning, and it is morning these players should be shown
        this.sync.apply(response.sky());
    }
}
