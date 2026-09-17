package net.civmc.shards.paper.sky;

import java.util.logging.Level;
import java.util.logging.Logger;
import net.civmc.shards.api.SkyState;
import net.civmc.shards.api.SkyStateRequest;
import net.civmc.shards.api.SkyStateResponse;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Keeps this shard's sky the one the proxy says the network is under.
 *
 * <p>The ground either side of a border is identical - every shard generates the same world from the
 * same seed - so a sky that disagrees is the one thing that gives away that a crossing changed
 * servers. Walking from noon into a thunderstorm was the first thing anyone noticed when the border
 * started working.</p>
 *
 * <p>Time is corrected rather than driven. The daylight cycle is left running, so the sky moves
 * smoothly between polls and carries on moving if the proxy goes quiet; this only steps in when the
 * local clock has drifted far enough to be seen. Weather is the opposite: its cycle is turned off,
 * because a cycle rolling its own storms is the thing being replaced, and there is nothing to
 * interpolate between two answers.</p>
 *
 * <p>Only the overworld-like worlds are touched. The nether and the end have no weather and no sky
 * to speak of, and their time is not what a player sees.</p>
 */
public final class SkySync {

    // How far the local clock may drift before it is pulled back. Two seconds of game time: smaller
    // than anything a player can see in the sky, and comfortably larger than a poll's round trip, so
    // a healthy shard is corrected rarely rather than every time it is asked
    private static final long DRIFT_TOLERANCE_TICKS = 40L;

    private final JavaPlugin plugin;
    private final ShardsClient client;
    private final String serverName;
    private final Logger logger;
    // Said once rather than every poll: a proxy that is not answering is already loud elsewhere, and
    // the sky is the least of what is wrong
    private boolean reportedFailure;
    private boolean pinnedWeatherCycle;

    public SkySync(final JavaPlugin plugin, final ShardsClient client, final String serverName,
                   final Logger logger) {
        this.plugin = plugin;
        this.client = client;
        this.serverName = serverName;
        this.logger = logger;
    }

    /**
     * Asks what the sky is, and applies the answer on the main thread.
     */
    public void poll() {
        if (!this.client.isReady()) {
            return;
        }
        this.client.skyState(SkyStateRequest.create(this.serverName))
            .whenComplete((response, error) -> Bukkit.getScheduler().runTask(this.plugin,
                () -> applyAnswer(response, error)));
    }

    private void applyAnswer(final SkyStateResponse response, final Throwable error) {
        if (error != null || response == null || response.failed()) {
            if (!this.reportedFailure) {
                this.reportedFailure = true;
                this.logger.log(Level.WARNING, "Could not read the network's sky, so this shard's own clock "
                    + "and weather are what players see until the proxy answers again", error);
            }
            return;
        }
        if (this.reportedFailure) {
            this.reportedFailure = false;
            this.logger.info("The network's sky is readable again");
        }
        apply(response.sky());
    }

    /**
     * Puts one answer into every world this shard shows a sky in.
     *
     * <p>Public because a night skip applies its answer through here too: the shard that slept is
     * where the people who slept are, and making them wait out a poll to see morning is exactly the
     * delay they would notice.</p>
     */
    public void apply(final SkyState sky) {
        for (final World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() != World.Environment.NORMAL) {
                continue;
            }
            applyTime(world, sky);
            applyWeather(world, sky);
        }
        if (!this.pinnedWeatherCycle) {
            this.pinnedWeatherCycle = true;
            this.logger.info("Weather is now the proxy's to decide, so this server's own weather cycle is off");
        }
    }

    private void applyTime(final World world, final SkyState sky) {
        if (Math.abs(world.getFullTime() - sky.fullTime()) <= DRIFT_TOLERANCE_TICKS) {
            return;
        }
        // Full time rather than time of day, so shards agree about which day it is - the moon phase
        // is the day count modulo eight, and two shards a day apart would show different moons over
        // the same continuous ground
        world.setFullTime(sky.fullTime());
    }

    private void applyWeather(final World world, final SkyState sky) {
        // Turned off here rather than once at startup: worlds can be loaded later, and a world that
        // arrives with its own cycle running would drift away from everybody else's weather
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        if (world.hasStorm() != sky.raining()) {
            world.setStorm(sky.raining());
        }
        // After the storm, never before: a world cannot thunder without raining, and setting the
        // storm off clears thunder with it
        if (world.isThundering() != sky.thundering()) {
            world.setThundering(sky.thundering());
        }
    }
}
