package net.civmc.shards.paper.mirror;

import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.civmc.shards.paper.border.ShardBorder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Stops this shard showing its own entities standing on ground it does not own.
 *
 * <p>The world does not stop at a border, so this server loads and ticks the chunks past its edge and
 * populates them - with its own mobs, its own dropped items, its own everything. None of it is real:
 * the shard that owns that ground has its own, different, actual contents there and cannot see any of
 * this. Looking across a border therefore shows a herd of cows that nobody else can see, standing
 * where a neighbour's building is.</p>
 *
 * <p>This is the first piece of the mirror, and it has to come first: there is no point drawing what
 * the neighbour really has there while a wrong copy is drawn on top of it.</p>
 *
 * <p>Nothing is removed. An entity out there is still a real entity of this server's, and deleting it
 * would be destroying a player's dropped items or their minecart. It is only hidden - which is also
 * why this makes the unsolved entity-crossing problem quieter rather than smaller: something that
 * rolls over the line now vanishes instead of sitting there visibly stuck.</p>
 */
public final class UnownedEntityView implements Listener {

    // Wide enough to cover every entity type the server tracks at range except players - animals are
    // the widest at 64 blocks, and players are excluded here anyway
    private static final int SWEEP_RADIUS = 64;
    private static final int SWEEP_HEIGHT = 64;

    private final JavaPlugin plugin;
    private final ShardBorder border;
    // What this class has hidden from each player, so it can put back anything that comes home. Only
    // ever entities it hid itself: something another plugin is hiding is not ours to reveal
    private final Map<UUID, Set<UUID>> hidden = new ConcurrentHashMap<>();

    public UnownedEntityView(final JavaPlugin plugin, final ShardBorder border) {
        this.plugin = plugin;
        this.border = border;
    }

    /**
     * Refuses to start showing an entity that is standing on somebody else's ground.
     *
     * <p>Cheaper than the sweep below and better than it: the entity is never sent at all, so there is
     * no moment where it appears and is then taken away. Paper retries tracking, so one that walks
     * back onto our own ground starts being shown again on its own.</p>
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onTrack(final PlayerTrackEntityEvent event) {
        if (shouldHide(event.getPlayer(), event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /**
     * Catches what the event above cannot: an entity that was already being shown when it wandered out.
     *
     * <p>Tracking begins once and is not reconsidered, so a cow that walks over the line stays visible
     * standing on the neighbour's ground - which is exactly the case this class exists for. Slow on
     * purpose, and skipped entirely for players nowhere near an edge.</p>
     */
    public void sweep(final Player viewer) {
        final Set<UUID> ours = this.hidden.computeIfAbsent(viewer.getUniqueId(), ignored -> new HashSet<>());
        revealWhatCameBack(viewer, ours);

        if (!this.border.isConfigured()
            || !this.border.outlineWithin(viewer.getLocation().getBlockX(), viewer.getLocation().getBlockZ(),
            SWEEP_RADIUS)) {
            return;
        }
        final List<Entity> nearby = viewer.getNearbyEntities(SWEEP_RADIUS, SWEEP_HEIGHT, SWEEP_RADIUS);
        for (final Entity entity : nearby) {
            // Already invisible to them - either the event above refused to track it, or somebody else
            // is hiding it. Either way it is not this sweep's to hide, and hiding it would make this
            // class responsible for revealing something it did not conceal
            if (!shouldHide(viewer, entity) || !viewer.canSee(entity)) {
                continue;
            }
            viewer.hideEntity(this.plugin, entity);
            ours.add(entity.getUniqueId());
        }
    }

    private void revealWhatCameBack(final Player viewer, final Set<UUID> ours) {
        ours.removeIf(entityUuid -> {
            final Entity entity = Bukkit.getEntity(entityUuid);
            if (entity == null) {
                // Gone: despawned, died, or unloaded. Nothing to reveal, and nothing to remember
                return true;
            }
            if (shouldHide(viewer, entity)) {
                return false;
            }
            viewer.showEntity(this.plugin, entity);
            return true;
        });
    }

    /**
     * Forgets a player who has left. What was hidden from them left with them, and holding their
     * entity ids would be a slow leak on a server people cross in and out of all day.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(final PlayerQuitEvent event) {
        this.hidden.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Whether an entity is one of ours standing where it has no business being seen.
     */
    private boolean shouldHide(final Player viewer, final Entity entity) {
        if (!this.border.isConfigured()) {
            return false;
        }
        if (entity instanceof Player) {
            // A player outside our areas is one mid-handover, which lasts a moment and is not a ghost.
            // Showing players from the far side is its own piece of work and not this one
            return false;
        }
        if (!entity.isVisibleByDefault()) {
            // Somebody is already deciding per player who sees this. The border's own glass panes are
            // exactly that, and they stand on the neighbour's block by definition - hiding them here
            // would erase the border itself
            return false;
        }
        if (entity.equals(viewer.getVehicle())) {
            // Never take away what somebody is riding, whatever ground it is over
            return false;
        }
        return this.border.isOutside(entity.getLocation());
    }
}
