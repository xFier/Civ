package net.civmc.shards.api.snapshot;

import java.util.List;
import java.util.Map;

/**
 * Everything about a player that travels with them between shards.
 *
 * <p>The destination server still loads its own {@code playerdata/<uuid>.dat} before this is applied,
 * because the vanilla storage is not replaced. So <strong>any field missing from this record silently
 * keeps whatever that server last wrote</strong> - a stale inventory or a stale XP level, with no
 * error anywhere. That is why the field list is exhaustive rather than a useful subset, and why
 * anything deliberately left out is named in {@link #NOT_CARRIED}.</p>
 *
 * <p>Binary parts are base64 so the whole record is one JSON document. Unknown or absent fields
 * deserialize to empty rather than throwing, so a row written by an older version still loads.</p>
 *
 * @param version the snapshot format, {@link #CURRENT_VERSION} when written here
 * @param inventory base64 of {@code ItemStack.serializeItemsAsBytes}, the whole inventory including
 *     armour and offhand
 * @param enderChest base64 of the ender chest's contents
 * @param persistentData base64 of the player's persistent data container, null when it is empty
 * @param attributeBaseValues attribute key to base value. Modifiers are not carried - they are
 *     derived from equipment, which travels in {@code inventory}, so re-applying them here would
 *     double them up
 * @param statistics encoded by {@link #statisticKey}, and only the non-zero ones
 * @param advancementCriteria advancement key to the criteria awarded on it, only for advancements
 *     with at least one
 * @param vehicle what the player was riding, set only by a transfer. Null on an ordinary quit, where
 *     the vehicle stays in the world and the server saves it itself - carrying it in that case would
 *     recreate it at the next login and leave two
 */
public record PlayerSnapshot(
    int version,
    String inventory,
    String enderChest,
    String persistentData,
    double health,
    int foodLevel,
    float saturation,
    float exhaustion,
    int xpLevel,
    float xpProgress,
    int totalExperience,
    int heldSlot,
    String gameMode,
    boolean allowFlight,
    boolean flying,
    float flySpeed,
    float walkSpeed,
    int remainingAir,
    int maximumAir,
    int fireTicks,
    int freezeTicks,
    Map<String, Double> attributeBaseValues,
    List<PotionEffectSnapshot> potionEffects,
    Map<String, List<String>> advancementCriteria,
    Map<String, Integer> statistics,
    List<String> discoveredRecipes,
    LocationSnapshot respawnLocation,
    VehicleSnapshot vehicle
) {

    public static final int CURRENT_VERSION = 1;

    /**
     * What the public API cannot round-trip, listed so it is a known limitation rather than a
     * discovery. The last death location can be read but not written, so the compass of a player who
     * dies and then crosses a border points at the destination's idea of their death, not the real
     * one. Anything else riding with the player, and anything on a lead, stays behind - only the
     * vehicle the player is on travels.
     */
    public static final List<String> NOT_CARRIED = List.of("lastDeathLocation", "passengers", "leashedEntities");

    public PlayerSnapshot {
        // Defaults rather than rejection: a row written before a field existed must still load, and
        // the alternative is a player who cannot log in because their stored data predates an upgrade
        attributeBaseValues = attributeBaseValues == null ? Map.of() : Map.copyOf(attributeBaseValues);
        potionEffects = potionEffects == null ? List.of() : List.copyOf(potionEffects);
        advancementCriteria = advancementCriteria == null ? Map.of() : Map.copyOf(advancementCriteria);
        statistics = statistics == null ? Map.of() : Map.copyOf(statistics);
        discoveredRecipes = discoveredRecipes == null ? List.of() : List.copyOf(discoveredRecipes);
    }

    /**
     * Statistics are keyed by the statistic alone when untyped, and by the statistic plus the material
     * or entity type when not - {@code MINE_BLOCK} means nothing without knowing which block.
     */
    public static String statisticKey(final String statistic, final String qualifier) {
        return qualifier == null ? statistic : statistic + "|" + qualifier;
    }

    /**
     * @return the qualifier half of {@link #statisticKey}, or null if the statistic is untyped
     */
    public static String statisticQualifier(final String key) {
        final int separator = key.indexOf('|');
        return separator < 0 ? null : key.substring(separator + 1);
    }

    public static String statisticName(final String key) {
        final int separator = key.indexOf('|');
        return separator < 0 ? key : key.substring(0, separator);
    }
}
