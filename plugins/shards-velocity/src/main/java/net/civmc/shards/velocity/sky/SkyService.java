package net.civmc.shards.velocity.sky;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import net.civmc.shards.api.SkyState;

/**
 * The one clock and the one weather the whole network runs on.
 *
 * <p>Here rather than derived on each shard from a shared formula, because weather has no formula to
 * derive it from and sleeping has to be able to move the clock. A shared epoch would agree about the
 * time for nothing, and then leave weather unshareable and beds unusable.</p>
 *
 * <p>The clock is read from the wall clock rather than counted up on a timer, so it does not drift
 * when the proxy is busy and does not stop when nothing is asking. Sleeping is an offset added to
 * it: the state is a base time and a moment, and everything else is arithmetic.</p>
 *
 * <p><strong>Nothing here is stored.</strong> A proxy restart puts the clock back to whatever the
 * wall clock says and the weather back to clear, so an accumulated night skip is lost and the sky
 * jumps once. Persisting it means a row in the database and a read at startup, which is worth doing
 * if the jump turns out to matter and is not worth doing in advance of that.</p>
 */
public final class SkyService {

    private static final long MILLIS_PER_TICK = 50L;

    // Vanilla-ish, and deliberately not exact: clear spells run into days, rain into hours, and a
    // thunderstorm is the short one. Exactness is not available anyway - the game rolls these per
    // world, and there is one sky here for all of them
    private static final long MIN_CLEAR_TICKS = TimeUnit.MINUTES.toMillis(10L) / MILLIS_PER_TICK;
    private static final long MAX_CLEAR_TICKS = TimeUnit.MINUTES.toMillis(180L) / MILLIS_PER_TICK;
    private static final long MIN_RAIN_TICKS = TimeUnit.MINUTES.toMillis(10L) / MILLIS_PER_TICK;
    private static final long MAX_RAIN_TICKS = TimeUnit.MINUTES.toMillis(40L) / MILLIS_PER_TICK;
    // A rainstorm turning into a thunderstorm rather than a separate kind of weather, which is how
    // the game has it: thunder implies rain, and clearing one clears the other
    private static final int THUNDER_CHANCE_IN = 4;

    private final Random random = new Random();

    // Everything below is guarded by this object's monitor. The requests that read it arrive on
    // several broker consumer threads at once, and a night skip read-modify-writes two of these
    private long fullTimeAtEpoch;
    private long epochMillis;
    private boolean raining;
    private boolean thundering;
    private long weatherUntilMillis;

    /**
     * A clock started from the wall clock, so a proxy restart resumes the sky roughly where it left
     * off instead of dropping the network back to dawn of the first day.
     *
     * <p>It does mean the day count is an enormous number - the ticks since 1970 - which is harmless
     * but shows up in anything that reports the day. The alternative is storing the clock, and the
     * point of this is that there is nothing to store.</p>
     */
    public static SkyService fromWallClock() {
        return new SkyService(System.currentTimeMillis() / MILLIS_PER_TICK);
    }

    public SkyService(final long startingFullTime) {
        this.fullTimeAtEpoch = startingFullTime;
        this.epochMillis = System.currentTimeMillis();
        this.weatherUntilMillis = this.epochMillis + rollDuration(MIN_CLEAR_TICKS, MAX_CLEAR_TICKS);
    }

    /**
     * What every shard should be showing right now.
     */
    public synchronized SkyState current() {
        final long now = System.currentTimeMillis();
        advanceWeather(now);
        return new SkyState(fullTimeAt(now), this.raining, this.thundering);
    }

    /**
     * Moves the whole network on to the next morning, if it is still night.
     *
     * <p>Guarded by the time of day rather than trusted from the shard that asked, because two shards
     * can finish sleeping in the same second: the second request would otherwise skip a whole further
     * day, and everybody who was not asleep would lose it.</p>
     *
     * @return the sky afterwards, and whether this request is the one that moved it
     */
    public synchronized SkipOutcome skipNight() {
        final long now = System.currentTimeMillis();
        advanceWeather(now);
        final SkyState before = new SkyState(fullTimeAt(now), this.raining, this.thundering);
        if (!before.isNight()) {
            return new SkipOutcome(before, false);
        }
        final SkyState after = before.nextMorning();
        // Re-based on now, so the ticks that have already elapsed are not counted a second time
        this.fullTimeAtEpoch = after.fullTime();
        this.epochMillis = now;
        this.raining = false;
        this.thundering = false;
        this.weatherUntilMillis = now + rollDuration(MIN_CLEAR_TICKS, MAX_CLEAR_TICKS);
        return new SkipOutcome(after, true);
    }

    private long fullTimeAt(final long nowMillis) {
        // Never negative even if the wall clock steps backwards, which it can: a time sync moving the
        // host's clock back would otherwise make the record throw rather than merely stall the sky
        return Math.max(this.fullTimeAtEpoch, this.fullTimeAtEpoch + (nowMillis - this.epochMillis) / MILLIS_PER_TICK);
    }

    /**
     * Rolls the next spell of weather once the current one has run out.
     *
     * <p>On demand rather than on a timer. Nothing needs to know the weather except when it is being
     * asked for, and a timer would be a second thing that has to still be running for the sky to be
     * right.</p>
     */
    private void advanceWeather(final long nowMillis) {
        if (nowMillis < this.weatherUntilMillis) {
            return;
        }
        if (this.raining) {
            this.raining = false;
            this.thundering = false;
            this.weatherUntilMillis = nowMillis + rollDuration(MIN_CLEAR_TICKS, MAX_CLEAR_TICKS);
            return;
        }
        this.raining = true;
        this.thundering = this.random.nextInt(THUNDER_CHANCE_IN) == 0;
        this.weatherUntilMillis = nowMillis + rollDuration(MIN_RAIN_TICKS, MAX_RAIN_TICKS);
    }

    private long rollDuration(final long minTicks, final long maxTicks) {
        return (minTicks + this.random.nextLong(maxTicks - minTicks)) * MILLIS_PER_TICK;
    }

    /**
     * @param sky the sky after the request was handled
     * @param skipped whether this request is the one that moved the night on
     */
    public record SkipOutcome(SkyState sky, boolean skipped) {
    }
}
