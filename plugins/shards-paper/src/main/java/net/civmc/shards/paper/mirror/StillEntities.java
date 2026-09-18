package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.civmc.shards.api.mirror.MirroredEntity;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

/**
 * Describes the entities in a chunk that stay where they are put.
 *
 * <p>Read on the main thread, in the same step as the chunk snapshot and for the same reason: it is a
 * copy, and copying is all the main thread should be asked to do for somebody else's rendering.</p>
 *
 * <p><strong>Only frames and stands.</strong> Not because the rest do not matter but because they do
 * not stay still: a cow would have to be described every tick, which is the cost the block
 * announcements exist to avoid, and that wants its own machinery rather than a place on this one. A
 * frame is described when its chunk is read and not again until something changes it.</p>
 *
 * <p>These two in particular because a CivMC build is made of them. A shop is a frame showing what it
 * sells and a stand wearing what it offers, so drawing a shop's walls and none of its contents draws
 * an abandoned building.</p>
 */
public final class StillEntities {

    private StillEntities() {
    }

    /**
     * @return the frames and stands in this chunk, or an empty list. Main thread
     */
    public static List<MirroredEntity> in(final Chunk chunk) {
        final List<MirroredEntity> still = new ArrayList<>();
        for (final Entity entity : chunk.getEntities()) {
            final MirroredEntity described = describe(entity);
            if (described != null) {
                still.add(described);
            }
        }
        return still;
    }

    private static MirroredEntity describe(final Entity entity) {
        if (entity instanceof ItemFrame frame) {
            return new MirroredEntity(frame.getUniqueId(), frame.getType().name(),
                frame.getLocation().getX(), frame.getLocation().getY(), frame.getLocation().getZ(),
                frame.getLocation().getYaw(), frame.getLocation().getPitch(),
                frame.getFacing().name(), encode(frame.getItem()), Map.of());
        }
        if (entity instanceof ArmorStand stand) {
            final Location at = stand.getLocation();
            return new MirroredEntity(stand.getUniqueId(), stand.getType().name(),
                at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch(), "", "", worn(stand));
        }
        return null;
    }

    /**
     * What a living entity is wearing and holding, by slot name.
     *
     * <p>By name rather than by position, because equipment is one of the few things about an entity
     * that is not a numbered field on the wire - it is sent with the slot named - so nothing here has
     * to be learned or guessed, unlike the item in a frame.</p>
     *
     * <p>Shared with the mirrored players rather than written twice: a stand wearing a helmet and a
     * player wearing one are the same description and the same packet on the far side.</p>
     */
    static Map<String, String> worn(final LivingEntity stand) {
        final EntityEquipment equipment = stand.getEquipment();
        if (equipment == null) {
            return Map.of();
        }
        final Map<String, String> worn = new HashMap<>();
        for (final EquipmentSlot slot : EquipmentSlot.values()) {
            final String encoded = encode(equipmentIn(equipment, slot));
            if (!encoded.isEmpty()) {
                worn.put(slot.name(), encoded);
            }
        }
        return worn;
    }

    private static ItemStack equipmentIn(final EntityEquipment equipment, final EquipmentSlot slot) {
        try {
            return equipment.getItem(slot);
        } catch (final IllegalArgumentException notASlotThisEntityHas) {
            // Slots are added to the game over time and not every entity has every one. Asking and
            // being refused is cheaper than a list here that goes stale on the next version
            return null;
        }
    }

    /**
     * The server's own bytes for an item, not a material name.
     *
     * <p>What makes a shop readable is the name, the lore and the enchantments, so a material name
     * would draw the right block and the wrong thing. Both shards run the same version - they are one
     * network - so the same reader is on both ends of this.</p>
     */
    static String encode(final ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        try {
            return Base64.getEncoder().encodeToString(item.serializeAsBytes());
        } catch (final RuntimeException unserialisable) {
            // One odd item is not worth losing the frame it is in: the frame is still drawn, empty
            return "";
        }
    }
}
