package net.civmc.shards.api.mirror;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Where one player is standing, for the shards that can see that spot but are not serving them.
 *
 * <p>Carries their skin with them rather than relying on anything else having it. The proxy does put
 * every cross-shard player in everybody's tab list, which would be enough for a client to render the
 * right skin - but depending on that would mean this quietly showing blank people the day somebody
 * turns the tab list off.</p>
 *
 * <p>What they are wearing and holding travels with them too. It is sent at the rate positions are,
 * which was once the argument for leaving it out - but the skin is already in every one of these
 * messages and is several times the size of an armour set, so a message of its own would be saving
 * the small half. What it costs is paid on the sending side instead: the bytes for an item are worked
 * out when the item changes and not once a tick.</p>
 *
 * <p>Equipment is the one part of an entity that is <strong>not</strong> a numbered metadata field -
 * it is sent with its slot named - so nothing about this has to be learned off a real entity or
 * guessed. That is why armour arrives before skin layers do.</p>
 *
 * @param headYaw where the head is turned, which is not the same as {@code yaw} - the body lags the
 *     head, and without it everybody looks like they are strafing
 * @param equipment what they are wearing and holding, by {@code EquipmentSlot} name, each one Base64
 *     of the server's own item bytes - the same form a frame's item travels in
 */
public record MirrorPlayer(UUID uuid, String name, String skinTexture, String skinSignature,
                           double x, double y, double z, float yaw, float pitch, float headYaw,
                           boolean sneaking, boolean swimming, boolean gliding, boolean onGround,
                           Map<String, String> equipment) {

    public MirrorPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
    }
}
