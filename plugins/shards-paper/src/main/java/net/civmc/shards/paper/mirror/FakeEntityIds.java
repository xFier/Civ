package net.civmc.shards.paper.mirror;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Entity ids for things that are drawn but are not here.
 *
 * <p>Counted from the top downwards, where the server's own counter will not reach in any plausible
 * lifetime. A collision would have a client attach one of these to a real entity - somebody else's
 * movement applied to a real player, or an item frame's contents to a real chest minecart.</p>
 *
 * <p><strong>One counter for the whole plugin, on purpose.</strong> The mirrored players and the
 * mirrored frames both want ids nothing else is using, and two counters both starting at the top would
 * hand out the same ones to both - a collision between two things that are each carefully avoiding
 * colliding with the server. Anything else that draws something not really here takes its ids from
 * here too.</p>
 */
public final class FakeEntityIds {

    private static final AtomicInteger NEXT = new AtomicInteger(Integer.MAX_VALUE - 1);

    private FakeEntityIds() {
    }

    public static int next() {
        return NEXT.getAndDecrement();
    }
}
