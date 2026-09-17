package net.civmc.shards.paper.mirror;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.civmc.shards.api.PlayerPositionMessage;
import net.civmc.shards.api.mirror.MirrorPlayer;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Says where this shard's players are, for the shards that can see that ground.
 *
 * <p>Announced rather than asked for, on the same fanout as block changes and for the same reason:
 * knowing which neighbour is looking at which of our players would mean tracking it and keeping it
 * current as everybody walks.</p>
 *
 * <p>Unlike block changes these are not events but the latest state, so nothing is missed by dropping
 * one - the next is along in a tick. That is why they are sent every tick with a short expiry and no
 * durability, and why a message that arrives late is simply replaced rather than applied.</p>
 *
 * <p>Only players near this shard's own outline. Somebody a thousand blocks from any border is
 * nobody's business but this server's, and a server with nobody near an edge announces nothing at
 * all.</p>
 */
public final class MirrorPlayerPublisher {

    // Their tracking range is 160 blocks, the widest of any entity, and this costs only bandwidth -
    // a receiver shows only the ones near its own players
    private static final int PUBLISH_RADIUS = 192;

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;

    public MirrorPlayerPublisher(final ShardBorder border, final ShardsClient client, final String serverName) {
        this.border = border;
        this.client = client;
        this.serverName = serverName;
    }

    /**
     * Main thread, once a tick.
     */
    public void publish() {
        if (!this.border.isConfigured()) {
            return;
        }
        final Map<String, List<MirrorPlayer>> byWorld = new HashMap<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Location at = player.getLocation();
            if (!this.border.outlineWithin(at.getBlockX(), at.getBlockZ(), PUBLISH_RADIUS)) {
                continue;
            }
            byWorld.computeIfAbsent(at.getWorld().getName(), ignored -> new ArrayList<>()).add(describe(player, at));
        }
        for (final Map.Entry<String, List<MirrorPlayer>> world : byWorld.entrySet()) {
            this.client.publishPlayerPositions(
                PlayerPositionMessage.create(this.serverName, world.getKey(), world.getValue()));
        }
    }

    private static MirrorPlayer describe(final Player player, final Location at) {
        // Carried with them rather than looked up on the far side. The proxy does put every
        // cross-shard player in everybody's tab list, which would serve - but depending on that would
        // mean showing blank people the day somebody turns the tab list off
        String texture = "";
        String signature = "";
        for (final ProfileProperty property : player.getPlayerProfile().getProperties()) {
            if ("textures".equals(property.getName())) {
                texture = property.getValue();
                signature = property.getSignature() == null ? "" : property.getSignature();
            }
        }
        return new MirrorPlayer(player.getUniqueId(), player.getName(), texture, signature,
            at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), player.getEyeLocation().getYaw(),
            player.isSneaking(), player.isSwimming(), player.isGliding(), player.isOnGround());
    }
}
