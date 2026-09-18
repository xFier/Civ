package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.player.EquipmentSlot;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.bukkit.inventory.ItemStack;

/**
 * What something drawn from another shard is wearing, turned into a packet.
 *
 * <p>Shared by the stands and the players because it is the same thing twice: a helmet on a mirrored
 * armour stand and a helmet on a mirrored player are the same slot, the same bytes and the same
 * packet.</p>
 *
 * <p>Equipment is the one part of an entity that is <strong>not</strong> a numbered field on the
 * wire. It is sent with the slot named, so none of this has to be read off a real entity the way a
 * frame's item does - which is why armour could be drawn before skin layers could.</p>
 *
 * <p>Names this package can only mention once the packet library is known to have loaded, like
 * everything else here that does.</p>
 */
final class MirrorEquipment {

    private MirrorEquipment() {
    }

    /**
     * What has to be sent to get from one set of equipment to another.
     *
     * <p>A slot that has been emptied is in the answer as an empty stack, never left out. Leaving it
     * out would mean a helmet taken off on one shard staying on somebody's head on every other one
     * for as long as they were in sight, which is worse than never having drawn it.</p>
     *
     * @param was what this was last drawn wearing, empty for something just drawn
     * @param now Base64 item bytes by {@code EquipmentSlot} name, as a shard describes them
     * @return what can be drawn of the difference, which is empty when there is nothing to say
     */
    static List<Equipment> of(final Map<String, String> was, final Map<String, String> now) {
        final List<Equipment> equipment = new ArrayList<>();
        for (final Map.Entry<String, String> slot : now.entrySet()) {
            final EquipmentSlot where = slotOf(slot.getKey());
            final ItemStack item = decode(slot.getValue());
            if (where != null && item != null && !slot.getValue().equals(was.get(slot.getKey()))) {
                equipment.add(new Equipment(where, SpigotConversionUtil.fromBukkitItemStack(item)));
            }
        }
        for (final String slot : was.keySet()) {
            final EquipmentSlot where = slotOf(slot);
            if (where != null && !now.containsKey(slot)) {
                equipment.add(new Equipment(where,
                    com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY));
            }
        }
        return equipment;
    }

    /**
     * The same slot under the packet library's name for it. Named rather than numbered, which is why
     * equipment needs nothing learned - and why a slot this version does not have is skipped instead
     * of being sent as something else.
     */
    private static EquipmentSlot slotOf(final String bukkitSlot) {
        return switch (bukkitSlot) {
            case "HAND" -> EquipmentSlot.MAIN_HAND;
            case "OFF_HAND" -> EquipmentSlot.OFF_HAND;
            case "FEET" -> EquipmentSlot.BOOTS;
            case "LEGS" -> EquipmentSlot.LEGGINGS;
            case "CHEST" -> EquipmentSlot.CHEST_PLATE;
            case "HEAD" -> EquipmentSlot.HELMET;
            case "BODY" -> EquipmentSlot.BODY;
            case "SADDLE" -> EquipmentSlot.SADDLE;
            default -> null;
        };
    }

    static ItemStack decode(final String encoded) {
        try {
            return ItemStack.deserializeBytes(Base64.getDecoder().decode(encoded));
        } catch (final RuntimeException unreadable) {
            // One item a shard could describe and this one cannot read is not worth what it is on
            return null;
        }
    }
}
