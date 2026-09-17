package net.civmc.shards.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import net.civmc.shards.api.mirror.MirroredEntity;

/**
 * @param state the chunk as the owner has it, deflated by {@code ChunkStateCodec} and Base64 encoded.
 *     Null when the owner could not answer
 * @param failureMessage why it could not, blank when it could. A chunk the owner does not have loaded
 *     and will not load is a failure rather than an empty answer: pretending it is air would show a
 *     hole in the world where a building is
 * @param publisherId which run of the owning server numbered the announcements about this chunk, so a
 *     viewer can tell a restart from a missed announcement. Blank when the owner could not answer
 * @param revision how many changes to this chunk the owner had announced when it took this snapshot.
 *     This state plus every announcement numbered above it is the chunk as it stands, which is what
 *     lets a viewer notice that one never arrived. Zero when the owner could not answer
 * @param entities the chunk's entities that stay still - frames and stands - so a viewer can draw the
 *     contents of a build and not only its walls. Sent here rather than in the encoded state because
 *     they are few, and because the state's encoding is built for sixteen thousand blocks a section
 */
public record ChunkStateResponse(UUID requestId, String world, int chunkX, int chunkZ, String state,
                                 String failureMessage, String publisherId, long revision,
                                 List<MirroredEntity> entities, long completedAtEpochMillis) {

    public ChunkStateResponse {
        Objects.requireNonNull(requestId, "requestId");
        failureMessage = failureMessage == null ? "" : failureMessage;
        publisherId = publisherId == null ? "" : publisherId;
        entities = entities == null ? List.of() : List.copyOf(entities);
        Messages.requirePositive(completedAtEpochMillis, "completedAtEpochMillis");
    }

    public static ChunkStateResponse of(final UUID requestId, final String world, final int chunkX,
                                        final int chunkZ, final String state, final String publisherId,
                                        final long revision, final List<MirroredEntity> entities) {
        return new ChunkStateResponse(requestId, world, chunkX, chunkZ, state, "", publisherId, revision,
            entities, System.currentTimeMillis());
    }

    public static ChunkStateResponse error(final UUID requestId, final String failureMessage) {
        return new ChunkStateResponse(requestId, "", 0, 0, null, failureMessage, "", 0L, List.of(),
            System.currentTimeMillis());
    }

    public boolean failed() {
        return !this.failureMessage.isEmpty() || this.state == null;
    }
}
