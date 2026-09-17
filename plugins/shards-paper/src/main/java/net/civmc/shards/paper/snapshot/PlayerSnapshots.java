package net.civmc.shards.paper.snapshot;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.civmc.shards.api.snapshot.LocationSnapshot;
import net.civmc.shards.api.snapshot.PlayerSnapshot;
import net.civmc.shards.api.snapshot.PotionEffectSnapshot;
import net.civmc.shards.api.snapshot.VehicleSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

/**
 * Reads a player into a {@link PlayerSnapshot} and writes one back.
 *
 * <p>Both directions run on the main thread: the Bukkit accessors used here are not safe off it.</p>
 *
 * <p>Restoring deliberately overwrites rather than merges. The destination server has already loaded
 * its own copy of this player from disk by the time this runs, and that copy is stale by definition -
 * anything left merged would be the old server's value surviving the move.</p>
 */
public final class PlayerSnapshots {

    // Worked out once. Asking for a block statistic about a non-block throws, and letting that happen
    // across every material costs thousands of exceptions per pass - on the main thread, during a join
    private static final List<Material> BLOCK_MATERIALS = Arrays.stream(Material.values())
        .filter(Material::isBlock).toList();
    private static final List<Material> ITEM_MATERIALS = Arrays.stream(Material.values())
        .filter(Material::isItem).toList();

    private PlayerSnapshots() {
    }

    /**
     * Reads a player, without whatever they are riding.
     *
     * <p>For an ordinary save. The vehicle stays in the world and the server persists it like any
     * other entity, so carrying it here too would rebuild it at the next login and leave two.</p>
     */
    public static PlayerSnapshot capture(final Player player) {
        return capture(player, null);
    }

    /**
     * Reads a player along with what they are riding, for a handover to another shard - where the
     * vehicle cannot stay behind because the player is not coming back for it.
     */
    public static PlayerSnapshot captureForTransfer(final Player player) {
        return capture(player, Vehicles.capture(player));
    }

    private static PlayerSnapshot capture(final Player player, final VehicleSnapshot vehicle) {
        return new PlayerSnapshot(
            PlayerSnapshot.CURRENT_VERSION,
            encode(ItemStack.serializeItemsAsBytes(player.getInventory().getContents())),
            encode(ItemStack.serializeItemsAsBytes(player.getEnderChest().getContents())),
            capturePersistentData(player),
            player.getHealth(),
            player.getFoodLevel(),
            player.getSaturation(),
            player.getExhaustion(),
            player.getLevel(),
            player.getExp(),
            player.getTotalExperience(),
            player.getInventory().getHeldItemSlot(),
            player.getGameMode().name(),
            player.getAllowFlight(),
            player.isFlying(),
            player.getFlySpeed(),
            player.getWalkSpeed(),
            player.getRemainingAir(),
            player.getMaximumAir(),
            player.getFireTicks(),
            player.getFreezeTicks(),
            captureAttributes(player),
            capturePotionEffects(player),
            captureAdvancements(player),
            captureStatistics(player),
            captureRecipes(player),
            captureLocation(player.getRespawnLocation()),
            vehicle,
            player.getLocation().getYaw(),
            player.getLocation().getPitch(),
            player.getVelocity().getX(),
            player.getVelocity().getY(),
            player.getVelocity().getZ(),
            player.getFallDistance(),
            player.isSprinting(),
            player.isGliding(),
            player.isSwimming());
    }

    public static void restore(final Player player, final PlayerSnapshot snapshot) {
        player.getInventory().setContents(ItemStack.deserializeItemsFromBytes(decode(snapshot.inventory())));
        player.getEnderChest().setContents(ItemStack.deserializeItemsFromBytes(decode(snapshot.enderChest())));

        // Attributes before health: max health is an attribute, and setting health above the current
        // maximum throws, so a player whose maximum was raised elsewhere has to have it raised here first
        restoreAttributes(player, snapshot);
        final AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth == null ? snapshot.health() : Math.min(snapshot.health(), maxHealth.getValue()));

        player.setFoodLevel(snapshot.foodLevel());
        player.setSaturation(snapshot.saturation());
        player.setExhaustion(snapshot.exhaustion());
        player.setLevel(snapshot.xpLevel());
        player.setExp(snapshot.xpProgress());
        player.setTotalExperience(snapshot.totalExperience());
        player.getInventory().setHeldItemSlot(snapshot.heldSlot());

        final GameMode gameMode = parseGameMode(snapshot.gameMode());
        if (gameMode != null) {
            player.setGameMode(gameMode);
        }
        // Flight after game mode: switching to or from creative resets both of these
        player.setAllowFlight(snapshot.allowFlight());
        player.setFlying(snapshot.flying() && snapshot.allowFlight());
        player.setFlySpeed(snapshot.flySpeed());
        player.setWalkSpeed(snapshot.walkSpeed());

        player.setMaximumAir(snapshot.maximumAir());
        player.setRemainingAir(snapshot.remainingAir());
        player.setFireTicks(snapshot.fireTicks());
        player.setFreezeTicks(snapshot.freezeTicks());

        restorePersistentData(player, snapshot);
        restorePotionEffects(player, snapshot);
        restoreAdvancements(player, snapshot);
        restoreStatistics(player, snapshot);
        restoreRecipes(player, snapshot);
        restoreRespawnLocation(player, snapshot);
        // Last: the player has to exist where they are before anything can be put underneath them
        Vehicles.restore(player, snapshot.vehicle());
    }

    /**
     * Puts a player back into the motion they were already in.
     *
     * <p>Separate from {@link #restore} because it has to happen a tick later. On the tick a player
     * joins, the server sends them their position, and that overrides anything set here - so velocity
     * applied during the join is thrown away and they stop dead in mid-air.</p>
     *
     * <p>Gliding needs the same wait for a different reason: it is refused unless the player already
     * has elytra on, which is true only once the inventory from {@link #restore} has been applied.</p>
     */
    public static void restoreMotion(final Player player, final PlayerSnapshot snapshot) {
        player.setVelocity(new Vector(snapshot.velocityX(), snapshot.velocityY(), snapshot.velocityZ()));
        // Before gliding, which a fall can clear
        player.setFallDistance(snapshot.fallDistance());
        if (snapshot.gliding()) {
            player.setGliding(true);
        }
        // Sprinting is deliberately not restored, though the snapshot carries it. It is a state the
        // client starts and stops, and a client that never started it here will never stop it - so a
        // player who crossed mid-sprint stayed sprinting to everybody watching, for the rest of their
        // time on the shard. The momentum that actually matters is in the velocity above, and a client
        // still holding the key tells this server so on its first move anyway
        if (snapshot.swimming()) {
            player.setSwimming(true);
        }
    }

    private static String capturePersistentData(final Player player) {
        if (player.getPersistentDataContainer().isEmpty()) {
            return null;
        }
        try {
            return encode(player.getPersistentDataContainer().serializeToBytes());
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not serialize persistent data for " + player.getUniqueId(),
                exception);
        }
    }

    private static void restorePersistentData(final Player player, final PlayerSnapshot snapshot) {
        if (snapshot.persistentData() == null) {
            return;
        }
        try {
            // Merges into whatever the destination already had rather than replacing it, because the
            // API offers no clear. Keys this server set for a player it has never met are not a real
            // case, and the snapshot's values win on any key both hold
            player.getPersistentDataContainer().readFromBytes(decode(snapshot.persistentData()));
        } catch (final IOException exception) {
            throw new IllegalStateException("Could not restore persistent data for " + player.getUniqueId(),
                exception);
        }
    }

    private static Map<String, Double> captureAttributes(final Player player) {
        final Map<String, Double> baseValues = new LinkedHashMap<>();
        for (final Attribute attribute : Registry.ATTRIBUTE) {
            final AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                baseValues.put(attribute.getKey().toString(), instance.getBaseValue());
            }
        }
        return baseValues;
    }

    private static void restoreAttributes(final Player player, final PlayerSnapshot snapshot) {
        for (final Map.Entry<String, Double> entry : snapshot.attributeBaseValues().entrySet()) {
            final NamespacedKey key = NamespacedKey.fromString(entry.getKey());
            final Attribute attribute = key == null ? null : Registry.ATTRIBUTE.get(key);
            if (attribute == null) {
                // A shard running a build that knows an attribute this one does not. Skipped rather
                // than fatal: losing one attribute is better than refusing the whole restore
                continue;
            }
            final AttributeInstance instance = player.getAttribute(attribute);
            if (instance != null) {
                instance.setBaseValue(entry.getValue());
            }
        }
    }

    private static List<PotionEffectSnapshot> capturePotionEffects(final Player player) {
        final List<PotionEffectSnapshot> effects = new ArrayList<>();
        for (final PotionEffect effect : player.getActivePotionEffects()) {
            effects.add(new PotionEffectSnapshot(
                effect.getType().getKey().toString(),
                effect.getDuration(),
                effect.getAmplifier(),
                effect.isAmbient(),
                effect.hasParticles(),
                effect.hasIcon()));
        }
        return effects;
    }

    private static void restorePotionEffects(final Player player, final PlayerSnapshot snapshot) {
        for (final PotionEffect existing : player.getActivePotionEffects()) {
            player.removePotionEffect(existing.getType());
        }
        for (final PotionEffectSnapshot effect : snapshot.potionEffects()) {
            final NamespacedKey key = NamespacedKey.fromString(effect.type());
            final PotionEffectType type = key == null ? null : Registry.POTION_EFFECT_TYPE.get(key);
            if (type == null) {
                continue;
            }
            player.addPotionEffect(new PotionEffect(type, effect.duration(), effect.amplifier(),
                effect.ambient(), effect.particles(), effect.icon()));
        }
    }

    private static Map<String, List<String>> captureAdvancements(final Player player) {
        final Map<String, List<String>> awarded = new LinkedHashMap<>();
        Bukkit.advancementIterator().forEachRemaining(advancement -> {
            final Collection<String> criteria = player.getAdvancementProgress(advancement).getAwardedCriteria();
            if (!criteria.isEmpty()) {
                awarded.put(advancement.getKey().toString(), List.copyOf(criteria));
            }
        });
        return awarded;
    }

    /**
     * Puts back every advancement the player had, without telling the server about it again.
     *
     * <p>The first time someone reaches a given shard, its own copy of their playerdata is empty, so
     * every advancement they have ever earned is awarded here in one go. Left alone that announces
     * each of them to everybody online - a screenful of "has made the advancement" for a player who
     * walked ten blocks. The gamerule is turned off around the restore and put back exactly as it
     * was, which is safe because this all happens within one tick and nothing else can earn an
     * advancement in between.</p>
     *
     * <p><strong>The toasts cannot be suppressed.</strong> Awarding a criterion sends the client the
     * advancement packet that drives them, and there is no public API to grant one quietly. So a
     * player's first arrival on a shard still shows them their own advancement history as a stack of
     * popups. Fixing that properly needs NMS, which this plugin deliberately does not use; the cost
     * is bounded, since it happens once per player per shard and never again.</p>
     */
    private static void restoreAdvancements(final Player player, final PlayerSnapshot snapshot) {
        final World world = player.getWorld();
        final Boolean announced = world.getGameRuleValue(GameRules.SHOW_ADVANCEMENT_MESSAGES);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        try {
            Bukkit.advancementIterator().forEachRemaining(advancement -> {
                final List<String> wanted =
                    snapshot.advancementCriteria().getOrDefault(advancement.getKey().toString(), List.of());
                applyAdvancement(player, advancement, wanted);
            });
        } finally {
            // Put back whatever it was, including when the restore threw. Leaving it off would
            // silence advancements for everyone on this shard from then on
            world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, announced == null || announced);
        }
    }

    private static void applyAdvancement(final Player player, final Advancement advancement,
                                         final List<String> wanted) {
        final AdvancementProgress progress = player.getAdvancementProgress(advancement);
        // Revoke first: a criterion this server awarded and the source did not is exactly the stale
        // state the restore exists to clear
        for (final String criterion : List.copyOf(progress.getAwardedCriteria())) {
            if (!wanted.contains(criterion)) {
                progress.revokeCriteria(criterion);
            }
        }
        for (final String criterion : wanted) {
            progress.awardCriteria(criterion);
        }
    }

    private static Map<String, Integer> captureStatistics(final Player player) {
        final Map<String, Integer> statistics = new LinkedHashMap<>();
        for (final Statistic statistic : Statistic.values()) {
            switch (statistic.getType()) {
                case UNTYPED -> putIfNonZero(statistics, statistic.name(), null, player.getStatistic(statistic));
                case BLOCK, ITEM -> {
                    for (final Material material : materialsFor(statistic)) {
                        putIfNonZero(statistics, statistic.name(), material.name(),
                            player.getStatistic(statistic, material));
                    }
                }
                case ENTITY -> {
                    for (final EntityType entityType : EntityType.values()) {
                        try {
                            putIfNonZero(statistics, statistic.name(), entityType.name(),
                                player.getStatistic(statistic, entityType));
                        } catch (final IllegalArgumentException ignored) {
                            // not a valid pairing
                        }
                    }
                }
                default -> {
                }
            }
        }
        return statistics;
    }

    private static void putIfNonZero(final Map<String, Integer> statistics, final String statistic,
                                     final String qualifier, final int value) {
        // Only non-zero values are stored. The map would otherwise run to tens of thousands of zeroes
        // per player, and a statistic absent from the snapshot restores to zero anyway
        if (value != 0) {
            statistics.put(PlayerSnapshot.statisticKey(statistic, qualifier), value);
        }
    }

    private static void restoreStatistics(final Player player, final PlayerSnapshot snapshot) {
        // Zero everything this server thinks it knows before applying, or a statistic the source had
        // at zero keeps the destination's higher count
        for (final Statistic statistic : Statistic.values()) {
            clearStatistic(player, statistic);
        }
        for (final Map.Entry<String, Integer> entry : snapshot.statistics().entrySet()) {
            applyStatistic(player, entry.getKey(), entry.getValue());
        }
    }

    private static void clearStatistic(final Player player, final Statistic statistic) {
        try {
            switch (statistic.getType()) {
                case UNTYPED -> player.setStatistic(statistic, 0);
                case BLOCK, ITEM -> {
                    for (final Material material : materialsFor(statistic)) {
                        player.setStatistic(statistic, material, 0);
                    }
                }
                case ENTITY -> {
                    for (final EntityType entityType : EntityType.values()) {
                        setQuietly(() -> player.setStatistic(statistic, entityType, 0));
                    }
                }
                default -> {
                }
            }
        } catch (final IllegalArgumentException ignored) {
            // not a valid pairing
        }
    }

    private static void applyStatistic(final Player player, final String key, final int value) {
        final Statistic statistic;
        try {
            statistic = Statistic.valueOf(PlayerSnapshot.statisticName(key));
        } catch (final IllegalArgumentException exception) {
            // A statistic this build does not have; skipped rather than failing the whole restore
            return;
        }
        final String qualifier = PlayerSnapshot.statisticQualifier(key);
        setQuietly(() -> {
            if (qualifier == null) {
                player.setStatistic(statistic, value);
            } else if (statistic.getType() == Statistic.Type.ENTITY) {
                player.setStatistic(statistic, EntityType.valueOf(qualifier), value);
            } else {
                player.setStatistic(statistic, Material.valueOf(qualifier), value);
            }
        });
    }

    private static List<Material> materialsFor(final Statistic statistic) {
        return statistic.getType() == Statistic.Type.BLOCK ? BLOCK_MATERIALS : ITEM_MATERIALS;
    }

    private static void setQuietly(final Runnable action) {
        try {
            action.run();
        } catch (final IllegalArgumentException ignored) {
            // not a valid pairing on this build
        }
    }

    private static List<String> captureRecipes(final Player player) {
        final List<String> recipes = new ArrayList<>();
        for (final NamespacedKey key : player.getDiscoveredRecipes()) {
            recipes.add(key.toString());
        }
        return recipes;
    }

    private static void restoreRecipes(final Player player, final PlayerSnapshot snapshot) {
        final List<NamespacedKey> wanted = new ArrayList<>();
        for (final String recipe : snapshot.discoveredRecipes()) {
            final NamespacedKey key = NamespacedKey.fromString(recipe);
            if (key != null) {
                wanted.add(key);
            }
        }
        final List<NamespacedKey> known = new ArrayList<>(player.getDiscoveredRecipes());
        final List<NamespacedKey> toUndiscover = new ArrayList<>(known);
        toUndiscover.removeAll(wanted);
        if (!toUndiscover.isEmpty()) {
            player.undiscoverRecipes(toUndiscover);
        }
        // Only the ones they do not already have. Handing the whole list over works, but the recipe
        // book treats each as newly unlocked, so a player crossing back and forth would be shown the
        // same stack of recipe popups every time
        wanted.removeAll(known);
        if (!wanted.isEmpty()) {
            player.discoverRecipes(wanted);
        }
    }

    private static LocationSnapshot captureLocation(final Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return new LocationSnapshot(location.getWorld().getName(), location.getX(), location.getY(),
            location.getZ(), location.getYaw(), location.getPitch());
    }

    private static void restoreRespawnLocation(final Player player, final PlayerSnapshot snapshot) {
        final LocationSnapshot respawn = snapshot.respawnLocation();
        if (respawn == null) {
            player.setRespawnLocation(null, true);
            return;
        }
        final World world = Bukkit.getWorld(respawn.world());
        if (world == null) {
            // The source's respawn world does not exist here. Clearing sends them to this world's
            // spawn on death, which beats a respawn that silently fails
            player.setRespawnLocation(null, true);
            return;
        }
        player.setRespawnLocation(new Location(world, respawn.x(), respawn.y(), respawn.z(),
            respawn.yaw(), respawn.pitch()), true);
    }

    private static GameMode parseGameMode(final String gameMode) {
        if (gameMode == null) {
            return null;
        }
        try {
            return GameMode.valueOf(gameMode);
        } catch (final IllegalArgumentException exception) {
            return null;
        }
    }

    private static String encode(final byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(final String base64) {
        return Base64.getDecoder().decode(base64);
    }
}
