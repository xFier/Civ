package net.civmc.shards.api;

/**
 * The sky every shard is meant to be showing: the same moment of the same day, and the same weather.
 *
 * <p>Each shard otherwise runs its own clock and its own weather cycle, and the terrain across a
 * border is continuous because every shard generates the same world from the same seed - so the
 * ground matches exactly while the sky does not, and walking over a line can take you from noon into
 * a thunderstorm. That is more jarring than two separate servers disagreeing, precisely because
 * everything else about the crossing says it is one place.</p>
 *
 * @param fullTime the world time in ticks since the world began, so shards agree about which day it
 *     is as well as the time of day
 * @param raining whether it is raining, which is snow or nothing depending on where a player stands
 * @param thundering whether that rain is a thunderstorm. Never true while {@code raining} is false
 */
public record SkyState(long fullTime, boolean raining, boolean thundering) {

    /**
     * How long a day is, in ticks. Worth naming because a night skip lands on a multiple of it, and
     * that is the whole definition of morning.
     */
    public static final long TICKS_PER_DAY = 24_000L;

    /**
     * When a bed may be used, in ticks into the day. The same window the game itself uses, so a shard
     * deciding that its players are asleep and the proxy deciding that it is night agree.
     */
    private static final long NIGHT_BEGINS = 12_542L;
    private static final long NIGHT_ENDS = 23_460L;

    public SkyState {
        if (fullTime < 0L) {
            throw new IllegalArgumentException("fullTime must not be negative");
        }
        // Rain and thunder are set separately on a world, so an impossible pair reaching a shard
        // would leave it somewhere it cannot be talked out of: thundering with nothing falling
        if (thundering && !raining) {
            throw new IllegalArgumentException("A thunderstorm has to be raining");
        }
    }

    /**
     * The same sky, moved on to the next morning, with the storm blown out.
     *
     * <p>What sleeping does in an unsharded world, clearing the weather included.</p>
     */
    public SkyState nextMorning() {
        return new SkyState((this.fullTime / TICKS_PER_DAY + 1L) * TICKS_PER_DAY, false, false);
    }

    /**
     * Whether it is night in the sense that matters here: the part of the day a bed can be used in.
     */
    public boolean isNight() {
        final long timeOfDay = this.fullTime % TICKS_PER_DAY;
        return timeOfDay >= NIGHT_BEGINS && timeOfDay <= NIGHT_ENDS;
    }
}
