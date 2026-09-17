package net.civmc.shards.paper.mirror;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.PacketWrapper;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityHeadLook;
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
import java.util.concurrent.atomic.AtomicInteger;
import net.civmc.shards.api.PlayerPositionMessage;
import net.civmc.shards.api.mirror.MirrorPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
 * <p>Entity ids are taken from the top of the range downwards, where the server's own counter will
 * not reach in any plausible lifetime. A collision would mean the client attaching somebody else's
 * movement to a real entity.</p>
 *
 * <p><strong>No entity metadata is sent, and that is not an oversight.</strong> The obvious thing to
 * send is which skin layers to draw - without it the outer layer, the hat and jacket, is missing. It
 * was sent at field 17, which is what that field used to be; on this version 17 is a float and the
 * client refuses the packet and drops the connection outright. A cosmetic packet took every player
 * on both shards offline. Metadata goes back in when the right index is read off a real player rather
 * than inferred, and until then a mirrored player has a plain skin, no armour and no pose.</p>
 */
public final class MirrorPlayerView implements Listener, MirrorPlayers {

    // What a client is shown of a player, and roughly their own tracking range. Beyond this they are
    // removed rather than left standing, so somebody who walked away does not remain frozen in a field
    private static final double SHOW_WITHIN = 160.0;
    // How long a ghost survives without being mentioned again. Three ticks would be tighter but would
    // flicker on a hiccup; this is short enough that a shard going down does not leave people standing
    // around in it
    private static final long FORGET_AFTER_NANOS = TimeUnit.SECONDS.toNanos(3L);

    // Downwards from the top, far from anything the server will assign
    private final AtomicInteger nextEntityId = new AtomicInteger(Integer.MAX_VALUE - 1);
    // viewer -> the players they are being shown, and under what id
    private final Map<UUID, Map<UUID, Ghost>> ghosts = new ConcurrentHashMap<>();
    // Everyone recently announced, so one that stops being mentioned can be taken away
    private final Map<UUID, Long> lastHeardOf = new ConcurrentHashMap<>();

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
    }

    private void spawn(final Player viewer, final Map<UUID, Ghost> theirs, final MirrorPlayer subject) {
        final int entityId = this.nextEntityId.getAndDecrement();
        theirs.put(subject.uuid(), new Ghost(entityId));

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

    private record Ghost(int entityId) {
    }
}
