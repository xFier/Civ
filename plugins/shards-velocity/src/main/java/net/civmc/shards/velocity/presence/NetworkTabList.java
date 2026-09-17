package net.civmc.shards.velocity.presence;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.player.TabListEntry;
import java.util.Optional;
import java.util.UUID;
import net.kyori.adventure.text.Component;

/**
 * Puts the players on the other shards into everybody's tab list.
 *
 * <p>A shard only knows its own players, so the list a player sees is their shard's population rather
 * than the network's. The more evenly people spread across shards, the emptier each list looks - the
 * same fault as {@link NetworkListCommand}, but the one everybody has open all the time.</p>
 *
 * <p>Only the players on <em>other</em> servers are added. The one a player is on sends its own list
 * entries, and adding a second entry for somebody already in it would be the same player twice.</p>
 *
 * <p>The proxy clears a player's tab list whenever they change server, so a crossing loses every entry
 * added here and they have to be put back. That is why this rebuilds on
 * {@link ServerPostConnectEvent} rather than only adding people as they join.</p>
 */
public final class NetworkTabList {

    // Survival. The proxy does not know what a player on another shard is playing in, and the only
    // thing this changes is the grey overlay on a spectator's head in the list
    private static final int ASSUMED_GAME_MODE = 0;

    private final ProxyServer proxyServer;

    public NetworkTabList(final ProxyServer proxyServer) {
        this.proxyServer = proxyServer;
    }

    /**
     * Rebuilds the arriving player's own list, and corrects their entry in everybody else's.
     *
     * <p>Both halves are needed for a crossing: the player's list was emptied by the switch, and every
     * other player's list is now wrong about them in one direction or the other - the shard they left
     * will drop them, and the shard they arrived on will send them.</p>
     */
    @Subscribe
    public void onPostConnect(final ServerPostConnectEvent event) {
        final Player arrived = event.getPlayer();
        for (final Player other : this.proxyServer.getAllPlayers()) {
            if (other.getUniqueId().equals(arrived.getUniqueId())) {
                continue;
            }
            show(arrived, other);
            show(other, arrived);
        }
    }

    @Subscribe
    public void onDisconnect(final DisconnectEvent event) {
        final UUID goneUuid = event.getPlayer().getUniqueId();
        for (final Player viewer : this.proxyServer.getAllPlayers()) {
            viewer.getTabList().removeEntry(goneUuid);
        }
    }

    /**
     * Puts everybody in front of everybody, and refreshes the pings while it is there.
     *
     * <p>On a timer as well as on the events above, for two reasons. A latency shown once at the
     * moment somebody connected is wrong for the rest of their session, and a tab list full of
     * zero-millisecond players reads as broken. And a list rebuilt from scratch every few seconds
     * cannot stay wrong: any entry an event missed is put right without anything having to notice
     * that it was missed.</p>
     */
    public void sync() {
        for (final Player viewer : this.proxyServer.getAllPlayers()) {
            for (final Player subject : this.proxyServer.getAllPlayers()) {
                if (!viewer.getUniqueId().equals(subject.getUniqueId())) {
                    show(viewer, subject);
                }
            }
        }
    }

    /**
     * Makes {@code viewer} see {@code subject}, or stop seeing them, depending on whether the subject
     * is somewhere else.
     */
    private void show(final Player viewer, final Player subject) {
        if (onSameServer(viewer, subject)) {
            // Nothing at all, and in particular not a removal. A tab list entry is keyed by uuid, so
            // removing "ours" removes whatever is there - including the one the server they now share
            // has just sent. That takes away the client's only player-info for that uuid, which is
            // both the tab entry and what the player entity is rendered from, so they go invisible
            // until something re-sends it; if what re-sends it is an update with no profile attached,
            // they come back wearing the default skin. Leaving it alone is safe because the entry the
            // shared server sends replaces ours under the same uuid
            return;
        }
        final Optional<TabListEntry> existing = viewer.getTabList().getEntry(subject.getUniqueId());
        if (existing.isPresent()) {
            existing.get().setLatency((int) subject.getPing());
            return;
        }
        viewer.getTabList().addEntry(TabListEntry.builder()
            .tabList(viewer.getTabList())
            .profile(subject.getGameProfile())
            .displayName(Component.text(subject.getUsername()))
            .latency((int) subject.getPing())
            .gameMode(ASSUMED_GAME_MODE)
            .build());
    }

    /**
     * Whether two players are being served by the same backend.
     *
     * <p>A player with no current server is mid-connection, which counts as somewhere else: showing
     * them is better than dropping them out of the list for the second it takes to cross a border.</p>
     */
    private static boolean onSameServer(final Player first, final Player second) {
        final Optional<String> firstServer = first.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName());
        final Optional<String> secondServer = second.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName());
        return firstServer.isPresent() && firstServer.equals(secondServer);
    }
}
