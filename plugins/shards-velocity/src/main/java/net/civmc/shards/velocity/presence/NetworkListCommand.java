package net.civmc.shards.velocity.presence;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Answers {@code /list} for the whole network rather than for one shard.
 *
 * <p>A shard's own {@code /list} knows only the people on it, so a network split across shards reads
 * as several half-empty servers - and the more evenly the shards are used, the emptier each one
 * looks. That is the opposite of what the borders are for.</p>
 *
 * <p>Answered on the proxy, which already knows every player and which server each is on, so there is
 * nothing to ask for and nothing to keep in sync. Registering the name here is what takes it off the
 * shards: a command the proxy owns never reaches the backend.</p>
 */
public final class NetworkListCommand implements SimpleCommand {

    // Where a player sits while they are connecting or being handed between shards. Brief, but a
    // crossing passes through it, so leaving them out would make people flicker out of the list as
    // they walk over a border
    private static final String IN_BETWEEN = "connecting";

    private final ProxyServer proxyServer;
    private final List<String> serverOrder;

    /**
     * @param serverOrder the shards, then the holding server, in the order they should be listed.
     *     Config order rather than alphabetical: an operator reading this has the config's order in
     *     their head, and it is usually the order the world is laid out in
     */
    public NetworkListCommand(final ProxyServer proxyServer, final List<String> serverOrder) {
        this.proxyServer = proxyServer;
        this.serverOrder = List.copyOf(serverOrder);
    }

    @Override
    public void execute(final Invocation invocation) {
        final Map<String, List<String>> byServer = group();
        final int total = byServer.values().stream().mapToInt(List::size).sum();
        invocation.source().sendMessage(Component.text(
            total + (total == 1 ? " player online" : " players online"), NamedTextColor.GRAY));
        for (final Map.Entry<String, List<String>> entry : byServer.entrySet()) {
            invocation.source().sendMessage(Component.text(entry.getKey() + " (" + entry.getValue().size() + "): ",
                    NamedTextColor.GRAY)
                .append(Component.text(String.join(", ", entry.getValue()), NamedTextColor.WHITE)));
        }
    }

    /**
     * Everybody online, by the server they are on, in the configured order.
     *
     * <p>A server with nobody on it is left out entirely rather than listed as empty. The list is
     * about who is here, and a network of mostly quiet shards would otherwise be mostly empty
     * lines.</p>
     */
    private Map<String, List<String>> group() {
        final Map<String, List<String>> byServer = new LinkedHashMap<>();
        for (final Player player : this.proxyServer.getAllPlayers()) {
            byServer.computeIfAbsent(serverOf(player), ignored -> new ArrayList<>())
                .add(player.getUsername());
        }
        for (final List<String> names : byServer.values()) {
            names.sort(String.CASE_INSENSITIVE_ORDER);
        }
        final Map<String, List<String>> ordered = new LinkedHashMap<>();
        for (final String serverName : this.serverOrder) {
            final List<String> names = byServer.remove(serverName);
            if (names != null) {
                ordered.put(serverName, names);
            }
        }
        // Whatever is left is a backend this plugin was not configured with - a lobby, a build
        // server. Still people on the network, so still listed, after the shards and in a stable order
        byServer.entrySet().stream()
            .sorted(Comparator.comparing(entry -> entry.getKey().toLowerCase(Locale.ROOT)))
            .forEach(entry -> ordered.put(entry.getKey(), entry.getValue()));
        return ordered;
    }

    private static String serverOf(final Player player) {
        final Optional<ServerConnection> connection = player.getCurrentServer();
        return connection.map(server -> server.getServerInfo().getName()).orElse(IN_BETWEEN);
    }
}
