package net.civmc.shards.paper;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import net.civmc.shards.api.ServerStartupRequest;
import net.civmc.shards.api.ServerStartupResponse;
import net.civmc.shards.paper.border.ArrivalCue;
import net.civmc.shards.paper.border.BorderNotices;
import net.civmc.shards.paper.border.BorderOutlook;
import net.civmc.shards.paper.border.BorderRenderer;
import net.civmc.shards.paper.border.BorderView;
import net.civmc.shards.paper.border.GlassBorderRenderer;
import net.civmc.shards.paper.border.ParticleBorderRenderer;
import net.civmc.shards.paper.border.ShardBorder;
import net.civmc.shards.paper.border.ShardBorderListener;
import net.civmc.shards.paper.border.ShardRespawnListener;
import net.civmc.shards.paper.border.TransferService;
import net.civmc.shards.paper.config.ShardsPaperConfig;
import net.civmc.shards.paper.mirror.ChunkRevisions;
import net.civmc.shards.paper.mirror.ChunkStateProvider;
import net.civmc.shards.paper.mirror.MirrorMetrics;
import net.civmc.shards.paper.mirror.MirrorPlayerPublisher;
import net.civmc.shards.paper.mirror.MirrorPlayerView;
import net.civmc.shards.paper.mirror.MirrorPlayers;
import net.civmc.shards.paper.mirror.MirrorRepairListener;
import net.civmc.shards.paper.mirror.MirrorUpdatePublisher;
import net.civmc.shards.paper.mirror.MirrorView;
import net.civmc.shards.paper.mirror.UnownedEntityView;
import net.civmc.shards.paper.mirror.UnownedGroundListener;
import net.civmc.shards.paper.playerdata.OwnedPlayers;
import net.civmc.shards.paper.playerdata.PlayerDataListener;
import net.civmc.shards.paper.rabbitmq.ShardsClient;
import net.civmc.shards.paper.rabbitmq.ShardsServer;
import net.civmc.shards.paper.sky.SkyListener;
import net.civmc.shards.paper.sky.SkySync;
import net.civmc.shards.paper.snapshot.SnapshotVerifyCommand;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class ShardsPaperPlugin extends JavaPlugin {

    private static final long SHUTDOWN_DRAIN_SECONDS = 20L;
    private static final long SAVE_CHECK_TICKS = 20L;
    private static final int MAX_SAVES_PER_RUN = 4;
    // Half a second: often enough that the particles look continuous and that a neighbour going down
    // is noticed while the player is still stood at the border, rare enough to be nothing on a tick
    private static final long BORDER_VIEW_TICKS = 10L;
    private static final long UNOWNED_SWEEP_TICKS = 20L * 5L;
    // Once a second. A chunk that is already mirrored costs a lookup here, so this is about how long
    // after walking towards a border the far side fills in, not about how often work is done
    private static final long MIRROR_TICKS = 20L;
    private static final long MIRROR_REPORT_TICKS = 20L * 30L;
    private static final long STARTUP_RETRY_MIN_TICKS = 20L * 5L;
    private static final long STARTUP_RETRY_MAX_TICKS = 20L * 60L;

    private ShardsPaperConfig config;
    private BorderView view;
    private ShardsClient client;
    private OwnedPlayers owned;
    private TransferService transfers;
    private ShardsServer mirrorServer;
    private final ShardBorder border = new ShardBorder();
    // Read from the login thread, written from whichever thread the startup answer arrives on
    private volatile boolean startupComplete;
    private long startupRetryTicks = STARTUP_RETRY_MIN_TICKS;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            this.config = ShardsPaperConfig.from(getConfig());
        } catch (final IllegalArgumentException | IllegalStateException exception) {
            getLogger().log(Level.SEVERE, "Could not load config.yml", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.client = new ShardsClient(this.config.connectionFactory(), this.config.serverName(), this, getLogger(),
            this::completeStartupHandshake);
        this.client.start();
        this.owned = new OwnedPlayers(this.client, getLogger(), this.config.serverName());

        final BorderNotices notices = new BorderNotices();
        final BorderOutlook outlook = new BorderOutlook(this.client, this.config.serverName(), getLogger());
        this.view = new BorderView(this.border, outlook, notices, renderer());
        this.transfers = new TransferService(this, this.client, this.owned, getLogger(),
            this.config.serverName(), this.config.failureMessage(), notices, this.view);

        final ArrivalCue arrivalCue = new ArrivalCue(this.config.arrivalTitle(), this.config.arrivalSubtitle());
        getServer().getPluginManager().registerEvents(
            new PlayerDataListener(this, this.client, this.config.serverName(), this.config.failureMessage(),
                this.owned, this.transfers, () -> this.startupComplete, arrivalCue), this);
        if (!arrivalCue.isConfigured()) {
            getLogger().info("No arrival title configured, so a crossing into this shard is unannounced");
        }
        getServer().getPluginManager().registerEvents(
            new ShardBorderListener(this.border, this.transfers, notices, outlook), this);
        getServer().getPluginManager().registerEvents(
            new ShardRespawnListener(this, this.border, this.transfers, getLogger()), this);
        getCommand("shardsnapshot").setExecutor(new SnapshotVerifyCommand());
        startSkySync();
        startUnownedEntityView();
        startMirror(outlook);
        startPeriodicSave();
        startBorderView(this.view);
    }

    @Override
    public void onDisable() {
        // Kick first, so every quit handler runs and every save is sent while the plugin is still able
        // to send it. A player still online when the client closes has their data left owned by a
        // server that is about to stop existing
        for (final Player player : Bukkit.getOnlinePlayers()) {
            player.kick(Component.text("Server is shutting down"));
        }
        if (this.owned != null) {
            this.owned.drain(SHUTDOWN_DRAIN_SECONDS, TimeUnit.SECONDS);
        }
        if (this.view != null) {
            // Anything the border put in the world has to come back out of it. A display entity left
            // behind is invisible litter that nothing else will ever clean up
            this.view.close();
        }
        if (this.mirrorServer != null) {
            this.mirrorServer.close();
        }
        if (this.client != null) {
            this.client.close();
        }
    }

    /**
     * Whichever way this server has been told to draw its border.
     */
    private BorderRenderer renderer() {
        return switch (this.config.borderStyle()) {
            case PARTICLES -> new ParticleBorderRenderer();
            case GLASS -> new GlassBorderRenderer(this);
        };
    }

    /**
     * Stops this server showing, and stops it creating, entities on ground it does not own.
     *
     * <p>The world does not stop at a border, so this server populates the chunks past its edge with
     * a copy of nothing anybody else can see - a herd of cows standing where the neighbour has a
     * building. The first piece of showing what is really over there is to stop drawing what is
     * not.</p>
     */
    private void startUnownedEntityView() {
        if (!this.config.hideUnownedEntities()) {
            getLogger().warning("Not hiding entities on unowned ground: players will see this server's own "
                + "mobs and items standing past its border, which no other shard can see");
            return;
        }
        final UnownedEntityView view = new UnownedEntityView(this, this.border);
        getServer().getPluginManager().registerEvents(view, this);
        getServer().getPluginManager().registerEvents(new UnownedGroundListener(this.border), this);
        // Slow, because it only exists to catch entities that wandered out after they were already
        // being shown. Everything arriving is caught by the tracking event, which costs nothing
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                view.sweep(player);
            }
        }, UNOWNED_SWEEP_TICKS, UNOWNED_SWEEP_TICKS);
    }

    /**
     * Shows what the neighbouring shards really have on the ground past the border.
     *
     * <p>Every shard's world begins as a copy of the same map, so unmodified ground already matches -
     * but everything built on a neighbour since is missing from this server's copy, and the gap grows
     * for as long as the map lives. The neighbour is asked for the chunk as it really is and the
     * difference is sent to clients; nothing is ever written into this server's world, so none of it
     * can be reinforced, counted, broken or picked up.</p>
     */
    private void startMirror(final BorderOutlook outlook) {
        if (!this.config.mirrorChunks()) {
            getLogger().warning("Not mirroring neighbouring shards: the ground past a border will be shown "
                + "as this server's own untouched copy of it, which is the map as it was when the shards "
                + "were split");
            return;
        }
        final MirrorMetrics metrics = new MirrorMetrics();
        // Shared by the two halves that have to agree on one count: the publisher that numbers an
        // announcement, and the provider that says which number a snapshot was taken at
        final ChunkRevisions revisions = new ChunkRevisions();
        final MirrorPlayers players = startPlayerMirror();
        // As far as the client renders, which is what has to look right. The neighbour's own view
        // distance does not come into it - it is this server's players who are looking
        final MirrorView mirror = new MirrorView(this, this.border, outlook, this.client,
            this.config.serverName(), getLogger(), getServer().getViewDistance(), metrics);
        this.mirrorServer = new ShardsServer(this.config.connectionFactory(), this.config.serverName(), this,
            getLogger(), new ChunkStateProvider(this.border, revisions), metrics,
            // Announcements arrive on a broker thread and these read the world and send to players
            update -> getServer().getScheduler().runTask(this, () -> mirror.applyUpdate(update)),
            positions -> getServer().getScheduler().runTask(this, () -> players.apply(positions)));
        this.mirrorServer.start();
        getServer().getPluginManager().registerEvents(mirror, this);
        // A refused placement makes the client correct itself to what is really there, which for
        // another shard's ground is this server's own copy - so the mirror has to be drawn again
        getServer().getPluginManager().registerEvents(new MirrorRepairListener(this, mirror), this);

        // Tells the other shards what has just changed here, so none of them has to re-read a chunk to
        // find out. Flushed once a tick: a block changed several times in one tick is sent once
        final MirrorUpdatePublisher publisher = new MirrorUpdatePublisher(this.border, this.client,
            this.config.serverName(), getLogger(), revisions);
        getServer().getPluginManager().registerEvents(publisher, this);
        getServer().getScheduler().runTaskTimer(this, publisher::flush, 1L, 1L);

        // Where this shard's players are, every tick, for the shards that can see that ground. Sent
        // whether or not this server can show anybody: a neighbour may be able to even if we cannot
        final MirrorPlayerPublisher playerPublisher = new MirrorPlayerPublisher(this.border, this.client,
            this.config.serverName());
        getServer().getScheduler().runTaskTimer(this, playerPublisher::publish, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, players::expire, 20L, 20L);
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                mirror.update(player);
            }
        }, MIRROR_TICKS, MIRROR_TICKS);
        // What the mirror costs is not visible from anywhere else, and the two numbers it reports -
        // how long a chunk takes to read, and how many blocks really differ - are what decide whether
        // this survives a busy border
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> metrics.report(getLogger()),
            MIRROR_REPORT_TICKS, MIRROR_REPORT_TICKS);
    }

    /**
     * Shows the players on other shards, if there is anything here that can.
     *
     * <p>Showing a player who is not really here is the one thing the mirror cannot do over the public
     * API, so it needs PacketEvents - which is a soft dependency, and might not be installed, or might
     * be installed broken. Both have to end with this server running everything else rather than not
     * starting.</p>
     *
     * <p>Hence the care: the class that uses the library is never named until the library is known to
     * have loaded, and the construction is guarded against {@link NoClassDefFoundError} as well as
     * ordinary failure. A half-installed library throws that at the point of first use rather than at
     * load, which is how this took the border, the transfers and the sky down with it.</p>
     */
    private MirrorPlayers startPlayerMirror() {
        final Plugin packetEvents = getServer().getPluginManager().getPlugin("packetevents");
        if (packetEvents == null || !packetEvents.isEnabled()) {
            getLogger().info("PacketEvents is not installed, so players on other shards will not be shown. "
                + "Everything else about the mirror works without it");
            return MirrorPlayers.NONE;
        }
        try {
            final MirrorPlayerView view = new MirrorPlayerView();
            getServer().getPluginManager().registerEvents(view, this);
            return view;
        } catch (final RuntimeException | LinkageError exception) {
            getLogger().log(Level.SEVERE, "PacketEvents is installed but could not be used, so players on "
                + "other shards will not be shown. Everything else about the mirror is unaffected", exception);
            return MirrorPlayers.NONE;
        }
    }

    /**
     * Keeps this shard's sky the same as everybody else's.
     *
     * <p>The ground either side of a border is identical, so the sky is the one thing that gives a
     * crossing away. Each shard asks rather than being told, which means one that has just started or
     * just reconnected is right within a single interval without the proxy tracking who is listening.</p>
     */
    private void startSkySync() {
        if (this.config.skySyncSeconds() <= 0) {
            getLogger().warning("Sky sync is off: this server runs its own clock and weather, so a crossing "
                + "can take a player from noon into a thunderstorm");
            return;
        }
        final SkySync sync = new SkySync(this, this.client, this.config.serverName(), getLogger());
        getServer().getPluginManager().registerEvents(
            new SkyListener(this, this.client, sync, this.config.serverName(), getLogger()), this);
        final long ticks = this.config.skySyncSeconds() * 20L;
        getServer().getScheduler().runTaskTimer(this, sync::poll, ticks, ticks);
        getLogger().info("Following the network's sky, checked every " + this.config.skySyncSeconds() + "s");
    }

    /**
     * Writes back the players who are due, once a second.
     *
     * <p>Every second rather than once an interval, writing only those actually due and a few at a
     * time. Reading a player costs tens of milliseconds, so writing everyone on one tick would be a
     * stall that arrives on a fixed cycle - which looks like the server hitching for no reason.</p>
     */
    private void startPeriodicSave() {
        if (this.config.saveIntervalSeconds() <= 0) {
            getLogger().warning("Periodic saving is off: anything since a player arrived is lost if this "
                + "server is killed rather than stopped");
            return;
        }
        final long dueAfterNanos = TimeUnit.SECONDS.toNanos(this.config.saveIntervalSeconds());
        getServer().getScheduler().runTaskTimer(this, () -> this.owned.checkpointDue(dueAfterNanos, MAX_SAVES_PER_RUN),
            SAVE_CHECK_TICKS, SAVE_CHECK_TICKS);
        getLogger().info("Writing players back every " + this.config.saveIntervalSeconds() + "s");
    }

    /**
     * Keeps what players can see of the border up to date.
     *
     * <p>On a timer rather than on movement, because a border can change while somebody stands still:
     * the shard beyond it going down turns a doorway into a wall without the player taking a step.
     * The particles need repainting as they expire anyway.</p>
     */
    private void startBorderView(final BorderView view) {
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (final Player player : Bukkit.getOnlinePlayers()) {
                view.update(player);
            }
        }, BORDER_VIEW_TICKS, BORDER_VIEW_TICKS);
    }

    /**
     * Moving a player to another shard, for other plugins.
     *
     * <p>Every reason for leaving a shard goes through the same thing - walking over a border, a
     * rocket landing, an arrival - so that writing a player back, giving up ownership and moving them
     * has one implementation rather than one per reason.</p>
     *
     * <p>State travels exactly as it is. A caller that wants a player to arrive without something has
     * to take it off them first; that is a rule of whatever is moving them, not of the transfer.</p>
     *
     * @return empty until this plugin has enabled
     */
    public Optional<TransferService> getTransfers() {
        return Optional.ofNullable(this.transfers);
    }

    /**
     * Asks the proxy to drop whatever locks this server still holds, and to say which areas it owns.
     *
     * <p>Releasing is correct only while nobody is online: anything held under this server's name then
     * was left by the run before it, whereas the same call with players on would drop the locks of the
     * people currently being served. That is why this runs on the first connection after enable - and
     * why the retry below is safe, because a login is refused until this has succeeded.</p>
     */
    private void completeStartupHandshake() {
        this.client.startup(ServerStartupRequest.create(this.config.serverName()))
            .whenComplete(this::logStartupResult);
    }

    private void logStartupResult(final ServerStartupResponse response, final Throwable error) {
        if (error != null) {
            getLogger().log(Level.SEVERE, "Could not complete the startup handshake", error);
            retryStartupHandshake();
            return;
        }
        if (!response.success()) {
            getLogger().severe("Could not complete the startup handshake: " + response.failureMessage());
            retryStartupHandshake();
            return;
        }
        this.startupComplete = true;
        getLogger().info("Released " + response.releasedLockCount() + " stale player data locks for "
            + this.config.serverName());

        // The proxy is the only holder of the shard map, so the areas this server owns arrive with the
        // startup answer rather than being configured a second time here
        this.border.set(response.regions());
        if (response.regions().isEmpty()) {
            getLogger().info("This server owns no shard areas, so no border is enforced");
        } else {
            getLogger().info("Enforcing " + response.regions().size() + " shard area(s)");
        }
    }

    /**
     * Asks again, rather than giving up for the lifetime of the server.
     *
     * <p>This used to be a log line and nothing else, which was the worse half of the failure: the
     * areas this server owns arrive with the same answer, so a handshake that never completed left
     * {@link ShardBorder} empty - and an empty border owns everywhere, so the server carried on
     * serving players with no edge enforced and nothing saying so. Refusing logins until this
     * succeeds is what makes both the wait and the repeated release safe.</p>
     */
    private void retryStartupHandshake() {
        getLogger().warning("This server has no shard areas yet, so nobody may join it. Trying again in "
            + (this.startupRetryTicks / 20L) + "s");
        getServer().getScheduler().runTaskLaterAsynchronously(this, this::completeStartupHandshake,
            this.startupRetryTicks);
        this.startupRetryTicks = Math.min(this.startupRetryTicks * 2L, STARTUP_RETRY_MAX_TICKS);
    }
}
