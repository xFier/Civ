package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import io.github.retrooper.packetevents.util.SpigotConversionUtil;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.civmc.shards.api.mirror.MirroredEntity;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

/**
 * Draws the frames and stands a neighbouring shard has, on ground this one can see.
 *
 * <p>Without this the mirror draws a shop's walls and none of its contents: the blocks are there, the
 * frames showing what is for sale are not, and a street of shops reads as an abandoned one.</p>
 *
 * <p><strong>None of them exist here.</strong> They are packets and nothing else - no entity, nothing
 * in the world, nothing any plugin can find, nothing to take an item out of. That is the same
 * construction as the blocks and the mirrored players, and it is what makes duplication impossible
 * rather than merely forbidden: there is no object to take from.</p>
 *
 * <p>Every drawn entity is given <strong>a fresh id and a fresh uuid</strong>, never the owner's.
 * Every shard's world began as a copy of one map, so a frame from before the split exists on every
 * shard with the same uuid - and a client keys several things by uuid, whichever arrives second
 * losing. That has already caught three separate things; it does not get to catch a fourth.</p>
 *
 * <p>What cannot be drawn is left undrawn. The item in a frame needs a metadata field number, which is
 * read off this server's own entities rather than guessed - see {@link LearnedEntityDataLayout} - and
 * until one has been read the frame is drawn empty rather than with a likely-looking number. Equipment
 * needs no such thing: it is sent with its slot named, so a stand wears what it wears from the
 * first.</p>
 */
public final class MirrorEntityView implements MirrorEntities {

    private final LearnedEntityDataLayout layout;

    // viewer -> chunk -> the owner's entity id -> what we drew for it
    private final Map<UUID, Map<String, Map<UUID, Drawn>>> drawn = new ConcurrentHashMap<>();

    public MirrorEntityView(final LearnedEntityDataLayout layout) {
        this.layout = layout;
    }

    private record Drawn(int entityId, MirroredEntity as) {
    }

    @Override
    public void show(final Player viewer, final String world, final int chunkX, final int chunkZ,
                     final List<MirroredEntity> entities) {
        final Map<UUID, Drawn> here = this.drawn
            .computeIfAbsent(viewer.getUniqueId(), ignored -> new ConcurrentHashMap<>())
            .computeIfAbsent(key(world, chunkX, chunkZ), ignored -> new ConcurrentHashMap<>());

        final List<Integer> gone = new ArrayList<>();
        final Map<UUID, Drawn> stillThere = new HashMap<>();
        for (final MirroredEntity entity : entities) {
            final Drawn already = here.get(entity.id());
            if (already == null) {
                stillThere.put(entity.id(), draw(viewer, entity));
                continue;
            }
            if (already.as().equals(entity)) {
                stillThere.put(entity.id(), already);
                continue;
            }
            if (movedOrChangedShape(already.as(), entity)) {
                // Cheaper to think about than to move: these do not move, so one that has is a
                // different frame on a different block and is drawn as one
                gone.add(already.entityId());
                stillThere.put(entity.id(), draw(viewer, entity));
                continue;
            }
            dress(viewer, already.entityId(), already.as(), entity);
            stillThere.put(entity.id(), new Drawn(already.entityId(), entity));
        }
        for (final Map.Entry<UUID, Drawn> was : here.entrySet()) {
            if (!stillThere.containsKey(was.getKey())) {
                gone.add(was.getValue().entityId());
            }
        }
        here.clear();
        here.putAll(stillThere);
        destroy(viewer, gone);
    }

    private static boolean movedOrChangedShape(final MirroredEntity was, final MirroredEntity is) {
        return was.x() != is.x() || was.y() != is.y() || was.z() != is.z()
            || was.yaw() != is.yaw() || was.pitch() != is.pitch()
            || !was.type().equals(is.type()) || !was.facing().equals(is.facing());
    }

    private Drawn draw(final Player viewer, final MirroredEntity entity) {
        final int entityId = FakeEntityIds.next();
        send(viewer, new WrapperPlayServerSpawnEntity(entityId,
            // A uuid of our own, never the owner's - see the class comment
            Optional.of(UUID.randomUUID()), typeOf(entity),
            new Vector3d(entity.x(), entity.y(), entity.z()), entity.pitch(), entity.yaw(), entity.yaw(),
            facingOf(entity.facing()), Optional.empty()));
        dress(viewer, entityId, null, entity);
        return new Drawn(entityId, entity);
    }

    /**
     * Puts the item in the frame and the armour on the stand.
     */
    private void dress(final Player viewer, final int entityId, final MirroredEntity was,
                       final MirroredEntity entity) {
        if (was == null || !was.item().equals(entity.item())) {
            final ItemStack item = entity.item().isEmpty() ? null : MirrorEquipment.decode(entity.item());
            // An empty frame is sent as an empty stack rather than not sent at all, or an item taken
            // out over there goes on being shown here until the chunk is next read. An item this
            // server cannot read goes the same way, for the same reason
            final Optional<EntityData<?>> data = this.layout.itemData(bukkitTypeOf(entity),
                item == null
                    ? com.github.retrooper.packetevents.protocol.item.ItemStack.EMPTY
                    : SpigotConversionUtil.fromBukkitItemStack(item));
            // Empty means the field number has not been read off a real entity yet. The frame is drawn
            // empty rather than with a guess: a guessed field number is what took the network offline
            data.ifPresent(one -> send(viewer, new WrapperPlayServerEntityMetadata(entityId, List.of(one))));
        }
        final List<Equipment> worn = MirrorEquipment.of(
            was == null ? Map.of() : was.equipment(), entity.equipment());
        if (!worn.isEmpty()) {
            send(viewer, new WrapperPlayServerEntityEquipment(entityId, worn));
        }
    }

    @Override
    public void forget(final Player viewer, final String world, final int chunkX, final int chunkZ) {
        final Map<String, Map<UUID, Drawn>> chunks = this.drawn.get(viewer.getUniqueId());
        if (chunks == null) {
            return;
        }
        final Map<UUID, Drawn> here = chunks.remove(key(world, chunkX, chunkZ));
        if (here == null) {
            return;
        }
        final List<Integer> gone = new ArrayList<>(here.size());
        for (final Drawn one : here.values()) {
            gone.add(one.entityId());
        }
        destroy(viewer, gone);
    }

    @Override
    public void forget(final Player viewer) {
        // Nothing is sent: they have gone, and their client has forgotten everything anyway
        this.drawn.remove(viewer.getUniqueId());
    }

    private void destroy(final Player viewer, final List<Integer> entityIds) {
        if (entityIds.isEmpty()) {
            return;
        }
        final int[] ids = new int[entityIds.size()];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = entityIds.get(index);
        }
        send(viewer, new WrapperPlayServerDestroyEntities(ids));
    }

    private void send(final Player viewer, final PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    private static com.github.retrooper.packetevents.protocol.entity.type.EntityType typeOf(
        final MirroredEntity entity) {
        final com.github.retrooper.packetevents.protocol.entity.type.EntityType type =
            SpigotConversionUtil.fromBukkitEntityType(bukkitTypeOf(entity));
        // Falling back to an item frame rather than refusing: something is better drawn in the wrong
        // shape than left as a hole, and only frames and stands are ever described
        return type == null ? EntityTypes.ITEM_FRAME : type;
    }

    private static EntityType bukkitTypeOf(final MirroredEntity entity) {
        try {
            return EntityType.valueOf(entity.type());
        } catch (final IllegalArgumentException notAThingHere) {
            return EntityType.ITEM_FRAME;
        }
    }

    /**
     * Which way a hung entity faces, as the protocol counts directions.
     *
     * <p>The one number here that is not read off anything. It is safe in a way a metadata field is
     * not: it is a field that always exists and always holds an int, so the worst a wrong answer does
     * is hang a frame on the wrong wall - where a wrong metadata field is a protocol error and a
     * dropped connection.</p>
     */
    private static int facingOf(final String facing) {
        if (facing.isEmpty()) {
            return 0;
        }
        return switch (facing) {
            case "DOWN" -> 0;
            case "UP" -> 1;
            case "NORTH" -> 2;
            case "SOUTH" -> 3;
            case "WEST" -> 4;
            case "EAST" -> 5;
            default -> BlockFace.NORTH.ordinal();
        };
    }

    private static String key(final String world, final int chunkX, final int chunkZ) {
        return world + ' ' + chunkX + ' ' + chunkZ;
    }
}
