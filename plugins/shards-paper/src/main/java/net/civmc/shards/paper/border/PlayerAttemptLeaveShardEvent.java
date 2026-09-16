package net.civmc.shards.paper.border;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired before a player is handed to another shard, and cancellable.
 *
 * <p>This is the hook anything with a reason to keep someone here uses - combat tagging, pearls, a
 * plugin mid-interaction. Crossing a border is a server change, so a plugin that would refuse a
 * server change has to be able to refuse this, and it exists from the start rather than being
 * retrofitted once something has already been lost to it.</p>
 *
 * <p>Cancelling leaves the player standing where they were, still owned by this server.</p>
 */
public final class PlayerAttemptLeaveShardEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player player;
    private final Location target;
    private boolean cancelled;

    public PlayerAttemptLeaveShardEvent(final Player player, final Location target) {
        this.player = player;
        this.target = target;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Player getPlayer() {
        return this.player;
    }

    /**
     * @return where the player was trying to get to, or null when the destination is a named shard and
     *     that server will decide where they appear - which is the case for an arrival rather than a
     *     step across a border
     */
    public Location getTarget() {
        return this.target;
    }

    @Override
    public boolean isCancelled() {
        return this.cancelled;
    }

    @Override
    public void setCancelled(final boolean cancelled) {
        this.cancelled = cancelled;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }
}
