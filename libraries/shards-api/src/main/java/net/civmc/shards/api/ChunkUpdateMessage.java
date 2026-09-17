package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import net.civmc.shards.api.mirror.BlockUpdate;
import net.civmc.shards.api.mirror.MirroredEntity;

/**
 * Blocks that have just changed on one shard, told to everybody rather than asked for.
 *
 * <p>The only message here with no reply and no addressee. It goes to a fanout exchange and every
 * shard gets a copy, which is deliberate: a shard would otherwise have to know which of its
 * neighbours is looking at which of its chunks, and keep that up to date as players walk. Instead a
 * receiver drops anything about a chunk it has not fetched, which it can answer from its own cache
 * without asking anyone. Cheap, because only blocks near a border are published at all.</p>
 *
 * <p>This is what stops the mirror re-reading a whole band of chunks every minute to discover that
 * nothing has changed. Measured on the rig before it existed: seventy chunks re-read every thirty
 * seconds for a player who was standing still, to find nothing.</p>
 *
 * <p>One message per chunk, so a receiver can decide whether it cares before reading any blocks.</p>
 *
 * <p>Numbered per chunk, because a fanout with no acknowledgement gives a receiver no other way to
 * tell a message that never arrived from a neighbour where nothing has happened. A number that does
 * not follow on from the last one means the chunk is read again in full - nothing here is ever
 * replayed, so this is a counter and not a log.</p>
 *
 * <p>Entities that stay still - frames and stands - are announced as <strong>the whole of what the
 * chunk has</strong>, not as a list of what changed. There are a handful per chunk against sixteen
 * thousand blocks a section, so describing all of them costs less than working out which one somebody
 * touched, and it means a removal needs no message of its own: something absent from the list is gone.
 * {@code entitiesDescribed} says whether this message speaks about them at all, because an
 * announcement about blocks must not be read as saying there are no frames.</p>
 *
 * @param publisherId which run of the sending server numbered this. A viewer that sees it change knows
 *     the numbering has restarted, rather than reading the numbers as having run backwards
 * @param revision how many changes to this chunk this server has announced, this one included. Blocks
 *     and entities share the one count, so a missed announcement of either is noticed the same way
 * @param entitiesDescribed whether {@code entities} is this chunk's entities or merely empty
 */
public record ChunkUpdateMessage(String serverName, String world, int chunkX, int chunkZ,
                                 List<BlockUpdate> updates, String publisherId, long revision,
                                 boolean entitiesDescribed, List<MirroredEntity> entities,
                                 long createdAtEpochMillis) {

    public ChunkUpdateMessage {
        serverName = Messages.requireNonBlank(serverName, "serverName");
        world = Messages.requireNonBlank(world, "world");
        Objects.requireNonNull(updates, "updates");
        updates = List.copyOf(updates);
        publisherId = Messages.requireNonBlank(publisherId, "publisherId");
        Messages.requirePositive(revision, "revision");
        entities = entities == null ? List.of() : List.copyOf(entities);
        Messages.requirePositive(createdAtEpochMillis, "createdAtEpochMillis");
    }

    /**
     * Blocks that have changed, saying nothing about the chunk's entities.
     */
    public static ChunkUpdateMessage blocks(final String serverName, final String world, final int chunkX,
                                            final int chunkZ, final List<BlockUpdate> updates,
                                            final String publisherId, final long revision) {
        return new ChunkUpdateMessage(serverName, world, chunkX, chunkZ, updates, publisherId, revision,
            false, List.of(), System.currentTimeMillis());
    }

    /**
     * Every still entity the chunk has, saying nothing about its blocks. An empty list means it has
     * none, which is how the last one being broken is announced.
     */
    public static ChunkUpdateMessage entities(final String serverName, final String world,
                                              final int chunkX, final int chunkZ,
                                              final List<MirroredEntity> entities,
                                              final String publisherId, final long revision) {
        return new ChunkUpdateMessage(serverName, world, chunkX, chunkZ, List.of(), publisherId, revision,
            true, entities, System.currentTimeMillis());
    }
}
