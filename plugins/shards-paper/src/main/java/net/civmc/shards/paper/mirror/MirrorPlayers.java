package net.civmc.shards.paper.mirror;

import net.civmc.shards.api.PlayerPositionMessage;

/**
 * Showing the players who are on another shard, or not.
 *
 * <p>An interface with nothing from a packet library in it, so the rest of the plugin can hold one
 * without ever loading a class that might not be there. Showing a player who is not really here is
 * the one thing the mirror cannot do over the public API, and the library that can is optional - so
 * the type that uses it has to be optional too, all the way down to never being named unless it has
 * loaded.</p>
 *
 * <p>Written after this went wrong: a soft dependency that takes the whole plugin down with it when
 * it is missing is not soft. The border, the transfers and the sky all stopped because of a class
 * that could not be found for a feature nobody had asked for yet.</p>
 */
public interface MirrorPlayers {

    /**
     * Does nothing, for a server with no packet library. Everything else about the mirror works.
     */
    MirrorPlayers NONE = new MirrorPlayers() {
        @Override
        public void apply(final PlayerPositionMessage message) {
        }

        @Override
        public void expire() {
        }
    };

    void apply(PlayerPositionMessage message);

    void expire();
}
