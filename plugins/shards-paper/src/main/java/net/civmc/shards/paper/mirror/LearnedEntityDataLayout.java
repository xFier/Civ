package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataType;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import io.github.retrooper.packetevents.util.SpigotReflectionUtil;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/**
 * Reads the metadata field numbers off this server's own entities, instead of hardcoding them.
 *
 * <p>The server already sends metadata for its own entities, constantly, and those packets are correct
 * for the version it is running by definition. So there is no need to know that an item frame's item
 * is field 8 on this version: watch what the server sends about an item frame, find the field holding
 * an item, and that is the number. A version that moves it is followed without anybody noticing, and
 * one that removes it teaches nothing rather than teaching something wrong.</p>
 *
 * <p><strong>Identified by type, never by position.</strong> An item frame has exactly one field
 * holding an item, so a field holding an item is that one. A number is only believed once it has been
 * seen several times and <em>no other number</em> of that type has ever been seen for that kind of
 * entity; two candidates mean the question cannot be answered this way, and it is then answered not at
 * all. That is why {@link EntityDataLayout.Field} is a short list - a byte field cannot be picked out
 * like this, and a byte field is exactly what caused the trouble that led here.</p>
 *
 * <p>Contradicting evidence takes a field away again. Something that was being drawn stops being
 * drawn, which is the right way round: the alternative is carrying on sending a number that has just
 * been shown to be wrong.</p>
 *
 * <p>Cost, because this sees every entity metadata packet the server sends: one type comparison for
 * the packet, and one per field for the few that are of interest. Fields of interest are rare in that
 * stream - a dropped item announces its stack once, a pose is sent when somebody starts sneaking -
 * so almost every packet stops at the first comparison. Turning a packet's entity id into an entity is
 * deliberately <strong>not</strong> done here, because that reads the server's entity table and this
 * runs on a network thread; ids are handed to the main thread instead.</p>
 */
public final class LearnedEntityDataLayout extends PacketListenerAbstract implements EntityDataLayout {

    // Enough that one oddity cannot decide a field, few enough that a frame in somebody's hall settles
    // it within moments of being looked at
    private static final int CONFIDENT_AFTER = 4;
    // A backlog can only grow on a server busy enough to settle every question in seconds anyway
    private static final int PENDING_CAP = 512;

    private final Logger logger;

    // Handed from the network thread to the main thread, the only place an entity id may be turned
    // into an entity
    private final Queue<Sighting> pending = new ConcurrentLinkedQueue<>();
    private final Object lock = new Object();
    private final Map<EntityType, Map<Field, Candidate>> learned = new HashMap<>();

    public LearnedEntityDataLayout(final Logger logger) {
        this.logger = logger;
    }

    /**
     * Starts watching. Here rather than at the call site so that nothing outside this package has to
     * name the packet library to switch it on.
     */
    public void watch() {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    /**
     * @param viewer whose connection the packet was on, which is how the world it happened in is found
     *     later. A uuid rather than anything live, because this crosses from a network thread
     */
    private record Sighting(UUID viewer, int entityId, Field field, int index,
                            EntityDataType<?> dataType) {
    }

    /**
     * What has been seen for one field of one kind of entity. Guarded by {@code lock}.
     */
    private static final class Candidate {
        private final Set<Integer> indices = new HashSet<>();
        // The wire type as well as the number, because sending the right number with the wrong type is
        // the same protocol error as sending the wrong number. An item is carried as a plain stack on
        // some entities and an optional one on others, and only the packets say which
        private final Set<EntityDataType<?>> types = new HashSet<>();
        private int sightings;
        private boolean announced;

        private boolean settled() {
            return this.sightings >= CONFIDENT_AFTER && this.indices.size() == 1 && this.types.size() == 1;
        }
    }

    @Override
    public OptionalInt indexOf(final EntityType type, final Field field) {
        synchronized (this.lock) {
            final Map<Field, Candidate> fields = this.learned.get(type);
            final Candidate candidate = fields == null ? null : fields.get(field);
            if (candidate == null || !candidate.settled()) {
                return OptionalInt.empty();
            }
            return OptionalInt.of(candidate.indices.iterator().next());
        }
    }

    /**
     * The field holding an item on this kind of entity, ready to send, or empty when it has not been
     * read off a real one yet.
     *
     * <p>Built here rather than by the caller because the wire type is half the answer and is not
     * something the caller can be told over {@link EntityDataLayout} without naming the packet
     * library in it.</p>
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Optional<EntityData<?>> itemData(final EntityType type, final ItemStack item) {
        synchronized (this.lock) {
            final Map<Field, Candidate> fields = this.learned.get(type);
            final Candidate candidate = fields == null ? null : fields.get(Field.ITEM);
            if (candidate == null || !candidate.settled()) {
                return Optional.empty();
            }
            final EntityDataType<?> dataType = candidate.types.iterator().next();
            final int index = candidate.indices.iterator().next();
            final Object value = EntityDataTypes.OPTIONAL_ITEMSTACK.equals(dataType)
                ? Optional.ofNullable(item)
                : item;
            return Optional.of(new EntityData(index, dataType, value));
        }
    }

    /**
     * Network thread. Does as little as it possibly can.
     */
    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (!PacketType.Play.Server.ENTITY_METADATA.equals(event.getPacketType())) {
            return;
        }
        try {
            note(event.getUser().getUUID(), new WrapperPlayServerEntityMetadata(event));
        } catch (final RuntimeException unreadable) {
            // Never a line each: this sees every metadata packet on the server, and the only
            // consequence of failing to read one is learning nothing from it
            this.logger.finest(() -> "Could not read an entity metadata packet: " + unreadable);
        }
    }

    private void note(final UUID viewer, final WrapperPlayServerEntityMetadata metadata) {
        if (viewer == null) {
            return;
        }
        for (final EntityData<?> data : metadata.getEntityMetadata()) {
            final Field field = fieldOf(data.getType());
            if (field == null || this.pending.size() >= PENDING_CAP) {
                continue;
            }
            this.pending.add(new Sighting(viewer, metadata.getEntityId(), field, data.getIndex(),
                data.getType()));
        }
    }

    private static Field fieldOf(final EntityDataType<?> type) {
        if (EntityDataTypes.ITEMSTACK.equals(type) || EntityDataTypes.OPTIONAL_ITEMSTACK.equals(type)) {
            return Field.ITEM;
        }
        if (EntityDataTypes.ENTITY_POSE.equals(type)) {
            return Field.POSE;
        }
        return null;
    }

    /**
     * Turns what was seen into what is known. Main thread, because that is where the server's entity
     * table may be read.
     */
    public void settle() {
        Sighting sighting;
        while ((sighting = this.pending.poll()) != null) {
            final Player viewer = Bukkit.getPlayer(sighting.viewer());
            if (viewer == null) {
                continue;
            }
            // The world the packet was sent in, taken from whose connection it was on. An entity id is
            // only unique within a world, so asking without one is asking a question with two answers
            final Entity entity = SpigotReflectionUtil.getEntityById(viewer.getWorld(), sighting.entityId());
            if (entity == null) {
                // Gone already. Nothing can be said about a number without knowing what kind of entity
                // it came from, and a guess here would defeat the point of the whole class
                continue;
            }
            record(entity.getType(), sighting.field(), sighting.index(), sighting.dataType());
        }
    }

    private void record(final EntityType type, final Field field, final int index,
                        final EntityDataType<?> dataType) {
        synchronized (this.lock) {
            final Candidate candidate = this.learned
                .computeIfAbsent(type, ignored -> new EnumMap<>(Field.class))
                .computeIfAbsent(field, ignored -> new Candidate());
            final boolean wasSettled = candidate.settled();
            candidate.sightings++;
            final boolean knewIndex = !candidate.indices.add(index);
            final boolean knewType = !candidate.types.add(dataType);
            if (knewIndex && knewType && candidate.announced) {
                return;
            }
            if (wasSettled && candidate.indices.size() > 1) {
                this.logger.warning("Read " + field + " for " + type + " at index " + index
                    + " after settling on another; it cannot be told apart by its type after all and "
                    + "will stop being sent");
                return;
            }
            if (candidate.announced || !candidate.settled()) {
                return;
            }
            candidate.announced = true;
            this.logger.info("Read " + field + " for " + type + " at metadata index "
                + candidate.indices.iterator().next() + " off this server's own entities");
        }
    }
}
