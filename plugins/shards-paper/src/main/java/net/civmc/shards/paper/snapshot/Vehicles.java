package net.civmc.shards.paper.snapshot;

import java.util.Base64;
import java.util.UUID;
import net.civmc.shards.api.snapshot.VehicleSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.AbstractHorse;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.ChestedHorse;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Horse;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Steerable;
import org.bukkit.entity.Tameable;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

/**
 * Carrying the thing a player is riding across a border with them.
 *
 * <p>A vehicle is an entity of the shard it is standing in, so it cannot simply be pointed at another
 * server: it has to be described here, destroyed here, and built again there. The order matters and is
 * the whole risk in this class - a vehicle rebuilt before the original is gone is a duplicated horse,
 * and horses are worth duplicating. {@link #remove} is therefore called before the handover is sent,
 * and {@link #restore} only ever runs from a payload the destination was given.</p>
 */
public final class Vehicles {

    private Vehicles() {
    }

    /**
     * @return a description of what the player is riding, or null if they are riding nothing this can
     *     faithfully rebuild
     */
    public static VehicleSnapshot capture(final Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return null;
        }
        return new VehicleSnapshot(
            vehicle.getType().name(),
            vehicle.getCustomName(),
            vehicle instanceof LivingEntity living ? living.getHealth() : null,
            captureInventory(vehicle),
            vehicle instanceof Tameable tameable ? tameable.isTamed() : null,
            ownerUuid(vehicle),
            vehicle instanceof AbstractHorse horse ? horse.getDomestication() : null,
            vehicle instanceof AbstractHorse horse ? horse.getMaxDomestication() : null,
            vehicle instanceof AbstractHorse horse ? horse.getJumpStrength() : null,
            vehicle instanceof Horse horse ? horse.getColor().name() : null,
            vehicle instanceof Horse horse ? horse.getStyle().name() : null,
            vehicle instanceof ChestedHorse chested ? chested.isCarryingChest() : null,
            vehicle instanceof Steerable steerable ? steerable.hasSaddle() : null,
            vehicle instanceof Ageable ageable ? ageable.isAdult() : null,
            vehicle instanceof Ageable ageable ? ageable.getAge() : null,
            // A vehicle is simulated by this server, unlike a player, so what it says about its own
            // motion is the truth rather than the last thing anybody told it
            vehicle.getVelocity().getX(),
            vehicle.getVelocity().getY(),
            vehicle.getVelocity().getZ());
    }

    /**
     * Takes the vehicle out of this world, having captured it.
     *
     * <p>Called before the handover is sent rather than after it is confirmed. Between sending and
     * being told it worked, the destination may already have rebuilt the vehicle - so removing it
     * afterwards would leave a window with one at each end. Removing it first means the only failure
     * is a vehicle that briefly does not exist anywhere, which the caller puts back.</p>
     */
    public static void remove(final Player player) {
        final Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return;
        }
        vehicle.eject();
        vehicle.remove();
    }

    /**
     * Builds the vehicle again and seats the player on it.
     *
     * <p>Failing here costs the vehicle, not the player: they arrive standing where they would have
     * been riding, which is why this never throws out to the join handler.</p>
     */
    public static void restore(final Player player, final VehicleSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        final EntityType type;
        try {
            type = EntityType.valueOf(snapshot.type());
        } catch (final IllegalArgumentException exception) {
            // A vehicle this build does not have. The player keeps everything else
            return;
        }
        final Location at = player.getLocation();
        final Entity vehicle = player.getWorld().spawnEntity(at, type);
        apply(vehicle, snapshot);
        vehicle.addPassenger(player);
    }

    /**
     * Puts the rebuilt vehicle back into the motion it was in.
     *
     * <p>A tick after {@link #restore}, and for the same reason the player's own motion waits: the
     * join tick sends a position, and any motion set during it is discarded. Set on the join tick, a
     * minecart doing full speed into a border arrived stopped dead - which was the whole of what
     * crossing on rails felt like.</p>
     *
     * <p>Applied to the vehicle and not to the rider. A passenger's own velocity is not what moves
     * them; the thing carrying them is.</p>
     */
    public static void restoreMotion(final Player player, final VehicleSnapshot snapshot) {
        if (snapshot == null || snapshot.velocityX() == null) {
            // Written before motion was carried. Standing still is what it has always meant
            return;
        }
        final Entity vehicle = player.getVehicle();
        if (vehicle == null) {
            return;
        }
        vehicle.setVelocity(new Vector(snapshot.velocityX(), snapshot.velocityY(), snapshot.velocityZ()));
    }

    private static void apply(final Entity vehicle, final VehicleSnapshot snapshot) {
        if (snapshot.customName() != null) {
            vehicle.setCustomName(snapshot.customName());
            vehicle.setCustomNameVisible(true);
        }
        if (vehicle instanceof Ageable ageable && snapshot.age() != null) {
            // Age before anything sized: a baby horse rejects an adult's attributes
            ageable.setAge(snapshot.age());
            if (Boolean.TRUE.equals(snapshot.adult())) {
                ageable.setAdult();
            }
        }
        if (vehicle instanceof Tameable tameable && Boolean.TRUE.equals(snapshot.tamed())) {
            tameable.setTamed(true);
            if (snapshot.ownerUuid() != null) {
                // Only the AnimalTamer form is settable, and getOfflinePlayer resolves without the
                // owner having to be online or even on this shard
                tameable.setOwner(Bukkit.getOfflinePlayer(UUID.fromString(snapshot.ownerUuid())));
            }
        }
        if (vehicle instanceof AbstractHorse horse) {
            if (snapshot.maxDomestication() != null) {
                // Before domestication, which is rejected if it exceeds the maximum
                horse.setMaxDomestication(snapshot.maxDomestication());
            }
            if (snapshot.domestication() != null) {
                horse.setDomestication(Math.min(snapshot.domestication(), horse.getMaxDomestication()));
            }
            if (snapshot.jumpStrength() != null) {
                horse.setJumpStrength(snapshot.jumpStrength());
            }
        }
        if (vehicle instanceof ChestedHorse chested && snapshot.carryingChest() != null) {
            // Before the inventory: the chest is what makes those slots exist
            chested.setCarryingChest(snapshot.carryingChest());
        }
        if (vehicle instanceof Horse horse) {
            if (snapshot.horseColor() != null) {
                horse.setColor(Horse.Color.valueOf(snapshot.horseColor()));
            }
            if (snapshot.horseStyle() != null) {
                horse.setStyle(Horse.Style.valueOf(snapshot.horseStyle()));
            }
        }
        if (vehicle instanceof Steerable steerable && snapshot.saddled() != null) {
            steerable.setSaddle(snapshot.saddled());
        }
        if (vehicle instanceof LivingEntity living && snapshot.health() != null) {
            living.setHealth(Math.min(snapshot.health(), living.getAttribute(
                org.bukkit.attribute.Attribute.MAX_HEALTH).getValue()));
        }
        restoreInventory(vehicle, snapshot);
    }

    private static String captureInventory(final Entity vehicle) {
        if (!(vehicle instanceof InventoryHolder holder)) {
            return null;
        }
        final ItemStack[] contents = holder.getInventory().getContents();
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(contents));
    }

    private static void restoreInventory(final Entity vehicle, final VehicleSnapshot snapshot) {
        if (snapshot.inventory() == null || !(vehicle instanceof InventoryHolder holder)) {
            return;
        }
        holder.getInventory().setContents(
            ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(snapshot.inventory())));
    }

    private static String ownerUuid(final Entity vehicle) {
        if (vehicle instanceof Tameable tameable && tameable.getOwnerUniqueId() != null) {
            return tameable.getOwnerUniqueId().toString();
        }
        return null;
    }
}
