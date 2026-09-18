package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.pose.EntityPose;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityEquipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import net.civmc.shards.api.PlayerPositionMessage;
import net.civmc.shards.api.mirror.MirrorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Shows the players who are on another shard, standing on ground this one can see.
 *
 * <p>The thing that makes a border read as one world rather than two servers. Everything else the
 * mirror does is scenery; this is somebody waving at you from the far side of a line.</p>
 *
 * <p><strong>None of them exist here.</strong> They are packets and nothing else - no entity, nothing
 * in the world, nothing any plugin can find, nothing to hit and nothing to be hit by. That is not a
 * safeguard bolted on, it is the whole construction: an arrow shot at one of these passes through
 * because there is nothing there to stop it, and the border refuses the arrow at the edge anyway.</p>
 *
 * <p>Entity ids come from {@link FakeEntityIds}, which is shared with everything else that draws
 * something not really here: two counters both starting at the top of the range would hand the same
 * ids to both, a collision between two things each carefully avoiding one with the server.</p>
 *
 * <p>What they are wearing and holding is drawn, because <strong>equipment is not metadata</strong>:
 * it is sent with its slot named rather than as a numbered field, so there is nothing to read off a
 * real player and nothing to guess. Sent when a ghost is first drawn and again whenever it changes,
 * which is what keeps it off the wire for somebody who is only walking.</p>
 *
 * <p><strong>Metadata is sent again, and only at a number the server itself declared.</strong> Which
 * layers of a skin to draw - the hat and the jacket - and what the person is doing with themselves, so
 * somebody sneaking along a border is sneaking on the other side of it too. This is the packet that
 * once took every player on both shards offline, sent at field 17 because that is what 17 used to be.
 * Where the number comes from now is {@link ServerFieldNumbers}, and where the server will not say,
 * none of it is sent and a mirrored player has a plain skin and stands upright.</p>
 */
public final class MirrorPlayerView implements Listener, MirrorPlayers {

    // What a client is shown of a player, and roughly their own tracking range. Beyond this they are
    // removed rather than left standing, so somebody who walked away does not remain frozen in a field
    private static final double SHOW_WITHIN = 160.0;
    // How long a ghost survives without being mentioned again. Three ticks would be tighter but would
    // flicker on a hiccup; this is short enough that a shard going down does not leave people standing
    // around in it
    private static final long FORGET_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3L);

    private final LearnedEntityDataLayout layout;

    // viewer -> the players they are being shown, and under what id
    private final Map<UUID, Map<UUID, Ghost>> ghosts = new ConcurrentHashMap<>();
    // Everyone recently announced, so one that stops being mentioned can be taken away
    private final Map<UUID, Long> lastHeardOf = new ConcurrentHashMap<>();

    /**
     * @param layout where a metadata field number comes from, or null when nothing here can say
     */
    public MirrorPlayerView(final LearnedEntityDataLayout layout) {
        this.layout = layout;
    }

    /**
     * Applies one announcement. Main thread.
     */
    @Override
    public void apply(final PlayerPositionMessage message) {
        if (!available()) {
            return;
        }
        final long now = System.nanoTime();
        for (final MirrorPlayer subject : message.players()) {
            this.lastHeardOf.put(subject.uuid(), now);
            if (Bukkit.getPlayer(subject.uuid()) != null) {
                // They are here, so this server has a real entity for them and a ghost with the same
                // uuid would be the same person twice. This is also what clears a ghost the moment
                // somebody finishes crossing in
                forgetEverywhere(subject.uuid());
                continue;
            }
            for (final Player viewer : Bukkit.getOnlinePlayers()) {
                show(viewer, message.world(), subject);
            }
        }
    }

    /**
     * Takes away anybody who has stopped being announced - they walked out of range of every border,
     * disconnected, or their shard went down. Main thread, on a timer.
     */
    @Override
    public void expire() {
        if (!available()) {
            return;
        }
        final long now = System.nanoTime();
        this.lastHeardOf.entrySet().removeIf(entry -> {
            if (now - entry.getValue() < FORGET_AFTER_NANOS) {
                return false;
            }
            forgetEverywhere(entry.getKey());
            return true;
        });
    }

    private void show(final Player viewer, final String world, final MirrorPlayer subject) {
        final Map<UUID, Ghost> theirs = this.ghosts.computeIfAbsent(viewer.getUniqueId(),
            ignored -> new ConcurrentHashMap<>());
        final boolean visible = viewer.getWorld().getName().equals(world)
            && viewer.getLocation().distanceSquared(
                new Location(viewer.getWorld(), subject.x(), subject.y(), subject.z()))
            <= SHOW_WITHIN * SHOW_WITHIN;
        final Ghost existing = theirs.get(subject.uuid());
        if (!visible) {
            if (existing != null) {
                theirs.remove(subject.uuid());
                send(viewer, new WrapperPlayServerDestroyEntities(existing.entityId()));
            }
            return;
        }
        if (existing == null) {
            spawn(viewer, theirs, subject);
            return;
        }
        send(viewer, new WrapperPlayServerEntityTeleport(existing.entityId(),
            new Vector3d(subject.x(), subject.y(), subject.z()), subject.yaw(), subject.pitch(),
            subject.onGround()));
        send(viewer, new WrapperPlayServerEntityHeadLook(existing.entityId(), subject.headYaw()));
        if (existing.skinParts() != subject.skinParts() || existing.pose() != poseOf(subject)) {
            describe(viewer, existing.entityId(), subject);
            theirs.put(subject.uuid(), new Ghost(existing.entityId(), existing.equipment(),
                subject.skinParts(), poseOf(subject)));
        }
        if (!existing.equipment().equals(subject.equipment())) {
            // Only what they have taken off or picked up. These messages carry the whole of it every
            // tick so that nothing can be missed; sending the whole of it every tick is what would be
            // wasteful
            dress(viewer, existing.entityId(), existing.equipment(), subject.equipment());
            theirs.put(subject.uuid(), new Ghost(existing.entityId(), subject.equipment(),
                subject.skinParts(), poseOf(subject)));
        }
    }

    private void spawn(final Player viewer, final Map<UUID, Ghost> theirs, final MirrorPlayer subject) {
        final int entityId = FakeEntityIds.next();
        theirs.put(subject.uuid(), new Ghost(entityId, subject.equipment(), subject.skinParts(),
            poseOf(subject)));

        // The profile is what gives them their skin and their name tag. Sent even though the proxy
        // already puts cross-shard players in the tab list, so this does not quietly break the day
        // somebody turns that off
        final List<TextureProperty> textures = new ArrayList<>();
        if (!subject.skinTexture().isEmpty()) {
            textures.add(new TextureProperty("textures", subject.skinTexture(),
                subject.skinSignature().isEmpty() ? null : subject.skinSignature()));
        }
        final UserProfile profile = new UserProfile(subject.uuid(), subject.name(), textures);
        send(viewer, new WrapperPlayServerPlayerInfoUpdate(
            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
            new WrapperPlayServerPlayerInfoUpdate.PlayerInfo(profile)));

        send(viewer, new WrapperPlayServerSpawnEntity(entityId, Optional.of(subject.uuid()),
            EntityTypes.PLAYER, new Vector3d(subject.x(), subject.y(), subject.z()), subject.pitch(),
            subject.yaw(), subject.headYaw(), 0, Optional.empty()));
        send(viewer, new WrapperPlayServerEntityHeadLook(entityId, subject.headYaw()));
        dress(viewer, entityId, Map.of(), subject.equipment());
        describe(viewer, entityId, subject);
    }

    /**
     * Sends the two things about a person that are numbered fields rather than packets of their own:
     * which layers of their skin to draw, and what they are doing with themselves.
     *
     * <p>Both in one packet, and only the ones the server would say where to put. A field nobody can
     * give a number for is left out rather than filled in, which is the whole lesson of this class.</p>
     */
    private void describe(final Player viewer, final int entityId, final MirrorPlayer subject) {
        if (this.layout == null) {
            return;
        }
        final List<EntityData<?>> described = new ArrayList<>(2);
        this.layout.skinLayers((byte) subject.skinParts()).ifPresent(described::add);
        this.layout.pose(poseOf(subject)).ifPresent(described::add);
        if (!described.isEmpty()) {
            send(viewer, new WrapperPlayServerEntityMetadata(entityId, described));
        }
    }

    /**
     * What they are doing with themselves, in the order the game itself settles them: gliding beats
     * swimming, and swimming beats a crouch.
     */
    private static EntityPose poseOf(final MirrorPlayer subject) {
        if (subject.gliding()) {
            return EntityPose.FALL_FLYING;
        }
        if (subject.swimming()) {
            return EntityPose.SWIMMING;
        }
        if (subject.sneaking()) {
            return EntityPose.CROUCHING;
        }
        return EntityPose.STANDING;
    }

    /**
     * Puts their armour on and their tools in their hands.
     */
    private void dress(final Player viewer, final int entityId, final Map<String, String> was,
                       final Map<String, String> now) {
        final List<Equipment> worn = MirrorEquipment.of(was, now);
        if (worn.isEmpty()) {
            return;
        }
        send(viewer, new WrapperPlayServerEntityEquipment(entityId, worn));
    }

    /**
     * Takes one person's ghost away from everybody who was being shown it.
     *
     * <p>Only the entity is destroyed, never the profile. A profile is keyed by uuid and the proxy
     * keeps one of its own for every cross-shard player, so removing ours would take theirs with it -
     * which is exactly how the tab list learned to make people invisible.</p>
     */
    private void forgetEverywhere(final UUID subjectUuid) {
        for (final Map.Entry<UUID, Map<UUID, Ghost>> viewer : this.ghosts.entrySet()) {
            final Ghost ghost = viewer.getValue().remove(subjectUuid);
            if (ghost == null) {
                continue;
            }
            final Player player = Bukkit.getPlayer(viewer.getKey());
            if (player != null) {
                send(player, new WrapperPlayServerDestroyEntities(ghost.entityId()));
            }
        }
    }

    /**
     * Takes somebody's ghost away the instant they arrive here for real.
     *
     * <p>Lowest priority, and on join rather than on the next announcement, because the order is the
     * whole point. A client keys entities by uuid as well as by id, so while the ghost still holds
     * that uuid the real player is <em>refused</em> - "Duplicate entity UUID" - and destroying the
     * ghost afterwards then leaves nothing at all. Somebody who crossed a border was invisible until
     * they crossed back.</p>
     *
     * <p>This runs before the entity tracker sends the real spawn, so the uuid is free by the time
     * the client is told about them. The same clearing in the announcement handler stays as a
     * backstop; it is simply always too late to be the only one.</p>
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(final PlayerJoinEvent event) {
        this.lastHeardOf.remove(event.getPlayer().getUniqueId());
        forgetEverywhere(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.ghosts.remove(event.getPlayer().getUniqueId());
    }

    private static void send(final Player viewer, final PacketWrapper<?> packet) {
        PacketEvents.getAPI().getPlayerManager().sendPacket(viewer, packet);
    }

    /**
     * Whether packets can be sent at all. PacketEvents is a soft dependency: everything else about the
     * mirror works without it, and a shard that will not start is worse than one that cannot show
     * people across a border.
     */
    private static boolean available() {
        try {
            return PacketEvents.getAPI() != null && PacketEvents.getAPI().isLoaded();
        } catch (final RuntimeException | NoClassDefFoundError exception) {
            return false;
        }
    }

    /**
     * @param equipment what this ghost was last drawn wearing, so a packet goes only when it changes
     * @param skinParts the layers it was last drawn with, for the same reason
     * @param pose what it was last drawn doing, for the same reason
     */
    private record Ghost(int entityId, Map<String, String> equipment, int skinParts, EntityPose pose) {
    }
}
