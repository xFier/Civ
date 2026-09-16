package net.civmc.shards.api.snapshot;

import java.util.Objects;

/**
 * One active effect. The display flags are carried alongside duration and amplifier because an effect
 * restored without them looks different to the player even though it behaves the same.
 *
 * @param type the effect type's namespaced key
 */
public record PotionEffectSnapshot(String type, int duration, int amplifier, boolean ambient,
                                   boolean particles, boolean icon) {

    public PotionEffectSnapshot {
        Objects.requireNonNull(type, "type");
    }
}
