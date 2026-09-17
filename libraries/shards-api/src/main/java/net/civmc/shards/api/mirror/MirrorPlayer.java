package net.civmc.shards.api.mirror;

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
 * <p>No equipment yet, deliberately: it would have to be sent at the rate positions are, and armour
 * is worth a message of its own that goes only when it changes. Until then a player across a border
 * is shown unarmoured, which is a thing to know rather than a thing to read into.</p>
 *
 * @param headYaw where the head is turned, which is not the same as {@code yaw} - the body lags the
 *     head, and without it everybody looks like they are strafing
 */
public record MirrorPlayer(UUID uuid, String name, String skinTexture, String skinSignature,
                           double x, double y, double z, float yaw, float pitch, float headYaw,
                           boolean sneaking, boolean swimming, boolean gliding, boolean onGround) {

    public MirrorPlayer {
        Objects.requireNonNull(uuid, "uuid");
        Objects.requireNonNull(name, "name");
    }
}
