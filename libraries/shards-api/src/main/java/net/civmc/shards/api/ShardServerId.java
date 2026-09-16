package net.civmc.shards.api;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/**
 * Derives the identity a server owns player data under.
 *
 * <p>The id is computed from the server's name rather than configured, so the proxy and the server
 * itself arrive at the same value without a registry to look it up in and without two config files
 * to keep in step. A server that restarts mints the same id, which is what lets it recognise and
 * clear the locks its predecessor left behind.</p>
 *
 * <p>Two consequences follow from deriving it, and both are easy to walk into:</p>
 *
 * <ul>
 *   <li>Renaming a server abandons every lock held under its old name. The data is untouched, but
 *       the players holding those locks can no longer be claimed until the locks are released by
 *       hand.</li>
 *   <li>Two instances running under one name share an id, so neither can tell the other's locks from
 *       its own. Pointing a second server at a live database under a name already in use lets it
 *       clear locks that are not stale.</li>
 * </ul>
 */
public final class ShardServerId {

    private static final String NAMESPACE = "shards:server:";

    private ShardServerId() {
    }

    /**
     * @param serverName the server's name as the proxy knows it; matched case-insensitively, since
     *     the proxy resolves names that way and one server must not mint two ids
     * @throws IllegalArgumentException if the name is null or blank - every misconfigured server
     *     would otherwise share one id, which is the only collision that actually matters
     */
    public static UUID of(final String serverName) {
        if (serverName == null || serverName.isBlank()) {
            throw new IllegalArgumentException("Server name must not be blank");
        }
        // Locale.ROOT rather than the default: a Turkish default locale maps I to a dotless i, so the
        // same name would derive differently depending on where the server happens to run
        final String normalised = serverName.trim().toLowerCase(Locale.ROOT);
        return UUID.nameUUIDFromBytes((NAMESPACE + normalised).getBytes(StandardCharsets.UTF_8));
    }
}
