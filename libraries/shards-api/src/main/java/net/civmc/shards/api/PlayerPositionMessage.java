package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import net.civmc.shards.api.mirror.MirrorPlayer;

/**
 * Where a shard's players are standing, announced every tick to whoever can see that ground.
 *
 * <p>Sent on the same fanout as block changes and for the same reason: a shard would otherwise have
 * to know which neighbour is looking at which of its players and keep that current as everybody
 * walks. Every shard hears every announcement and shows the ones near its own players.</p>
 *
 * <p>Only players near this shard's own outline are in it, so a server where nobody is anywhere near
 * a border announces an empty list - and sends nothing at all.</p>
 *
 * <p>Unlike the block announcements, these are not events. A position is only ever the latest one, so
 * a message that arrives late is simply replaced by the next; nothing is missed by dropping one, which
 * is why they are sent with a short expiry and no durability at all.</p>
 */
public record PlayerPositionMessage(String serverName, String world, List<MirrorPlayer> players,
                                    long createdAtEpochMillis) {

    public PlayerPositionMessage {
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Objects.requireNonNull(players, "players");
        players = List.copyOf(players);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    public static PlayerPositionMessage create(final String serverName, final String world,
                                               final List<MirrorPlayer> players) {
        return new PlayerPositionMessage(serverName, world, players, System.currentTimeMillis());
    }
}
