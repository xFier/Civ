package net.civmc.shards.paper.mirror;

import java.util.OptionalInt;
import org.bukkit.entity.EntityType;

/**
 * Which numbered field of an entity's metadata carries which piece of it.
 *
 * <p>Entity metadata is sent as numbered fields with no names on the wire, and the numbers move
 * between versions of the game. <strong>Guessing one is how a purely cosmetic packet took every player
 * on both shards offline:</strong> a byte was sent at field 17 because that is what 17 used to be, and
 * on this version 17 is a float, so the client dropped the connection on a protocol error. The rule
 * written down afterwards was that an index must be read off a real entity rather than inferred, and
 * this is the type that does the reading.</p>
 *
 * <p>An interface with nothing from a packet library in it, for the same reason as
 * {@link MirrorPlayers}: the thing that can learn these numbers can only exist when that library has
 * loaded, so nothing else may name it.</p>
 */
public interface EntityDataLayout {

    /**
     * The pieces worth asking for. Only ones that can be told apart by their type alone, because that
     * is the whole basis on which they are identified - an entity has exactly one field holding an
     * item, so a field holding an item is that one. A byte is not identifiable this way and is why
     * skin layers are still missing from a mirrored player.
     */
    enum Field {
        /** What the entity is holding or displaying: the picture in a frame, a dropped stack. */
        ITEM,
        /** Standing, sneaking, swimming, gliding. */
        POSE
    }

    /**
     * Knows nothing, for a server with no packet library or one where the field has not been seen yet.
     * Callers draw what they can without it rather than guessing.
     */
    EntityDataLayout UNKNOWN = (type, field) -> OptionalInt.empty();

    /**
     * @return the index to send that field at, or empty when it has not been read off a real entity of
     *     that type yet. <strong>Empty means send nothing</strong>, never send a likely-looking number
     */
    OptionalInt indexOf(EntityType type, Field field);
}
