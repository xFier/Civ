package net.civmc.shards.api.mirror;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One of a shard's entities, described so another shard can draw a picture of it.
 *
 * <p>Only the ones that stay still. A neighbour's cows would have to be described every tick, which is
 * the problem the block announcements were built to avoid; an item frame is described when its chunk
 * is read and then not again until something changes it. The difference is not a matter of degree -
 * one rides the machinery that already exists and the other needs its own.</p>
 *
 * <p><strong>Frames and stands are what a CivMC build is made of.</strong> A shop is an item frame
 * showing what it sells and an armour stand wearing what it offers, so a border that draws the blocks
 * of a shop and none of its contents draws an empty wall and reads as an abandoned one.</p>
 *
 * <p>Items travel as the bytes the server itself writes them as, not as a material name, because what
 * makes a shop readable is the name, the lore and the enchantments rather than which block it is. Both
 * shards run the same version - they are the same network - so the same reader is on both ends.</p>
 *
 * @param id the entity's real id on the shard that owns it, used to tell one picture from another
 *     between reads. Nothing is ever keyed by it on the receiving side, where a picture gets an id of
 *     its own: every shard's world began as a copy of one map, so an entity from before the split has
 *     the same id on every shard, and that is a collision waiting to be found
 * @param type the Bukkit entity type's name
 * @param facing which way it is fixed to a block, a {@code BlockFace} name, empty for anything that is
 *     not hung on one
 * @param item what it is displaying, Base64 of the server's own item bytes, empty for nothing
 * @param equipment what it is wearing, by {@code EquipmentSlot} name, in the same form
 */
public record MirroredEntity(UUID id, String type, double x, double y, double z, float yaw, float pitch,
                             String facing, String item, Map<String, String> equipment) {

    public MirroredEntity {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        facing = facing == null ? "" : facing;
        item = item == null ? "" : item;
        equipment = equipment == null ? Map.of() : Map.copyOf(equipment);
    }
}
