package net.civmc.shards.paper.mirror;

import com.destroystokyo.paper.profile.ProfileProperty;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.civmc.shards.api.PlayerPositionMessage;
import net.civmc.shards.api.mirror.MirrorPlayer;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

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
    // Everything a player can be seen wearing or holding. Read by name, so a slot this version does
    // not have simply is not in the list
    private static final EquipmentSlot[] SLOTS = EquipmentSlot.values();

    private final ShardBorder border;
    private final ShardsClient client;
    private final String serverName;
    // What each player was last seen wearing, and the bytes for it. Turning an item into bytes is the
    // one expensive thing here and armour does not change from tick to tick, so it is done when the
    // items themselves differ rather than once a tick for everybody standing near a border
    private final Map<UUID, Worn> worn = new ConcurrentHashMap<>();

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
        // Nobody near a border wears anything this has to remember
        this.worn.keySet().removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
        for (final Map.Entry<String, List<MirrorPlayer>> world : byWorld.entrySet()) {
            this.client.publishPlayerPositions(
                PlayerPositionMessage.create(this.serverName, world.getKey(), world.getValue()));
        }
    }

    private MirrorPlayer describe(final Player player, final Location at) {
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
            player.isSneaking(), player.isSwimming(), player.isGliding(), player.isOnGround(),
            worn(player));
    }

    /**
     * What a player is wearing and holding, worked out again only when it has changed.
     *
     * <p>Described in every message rather than in one of its own when it changes. A message that
     * only carries a change has to arrive, and these are sent with a short expiry and no durability
     * precisely because none of them has to - so armour that missed its one announcement would stay
     * wrong until the next time somebody moved a helmet. The whole of it, every tick, cannot be
     * wrong for longer than a tick.</p>
     *
     * <p>What that would have cost is the serialising, not the sending: the skin is already in every
     * one of these messages and is larger than an armour set. So the items are compared - they are
     * already objects in memory - and only turned into bytes when they differ.</p>
     */
    private Map<String, String> worn(final Player player) {
        final EntityEquipment equipment = player.getEquipment();
        if (equipment == null) {
            return Map.of();
        }
        final ItemStack[] wearing = new ItemStack[SLOTS.length];
        for (int slot = 0; slot < SLOTS.length; slot++) {
            wearing[slot] = itemIn(equipment, SLOTS[slot]);
        }
        final Worn last = this.worn.get(player.getUniqueId());
        if (last != null && Arrays.equals(last.items(), wearing)) {
            return last.encoded();
        }
        final Map<String, String> encoded = StillEntities.worn(player);
        this.worn.put(player.getUniqueId(), new Worn(wearing, encoded));
        return encoded;
    }

    private static ItemStack itemIn(final EntityEquipment equipment, final EquipmentSlot slot) {
        try {
            return equipment.getItem(slot);
        } catch (final IllegalArgumentException notASlotAPlayerHas) {
            return null;
        }
    }

    private record Worn(ItemStack[] items, Map<String, String> encoded) {
    }
}
