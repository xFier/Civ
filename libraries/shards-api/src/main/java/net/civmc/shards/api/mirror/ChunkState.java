package net.civmc.shards.api.mirror;

import java.util.List;

/**
 * A whole chunk as the shard that owns it really has it.
 *
 * <p>Sent as state rather than as a list of changes, which is the decision the whole mirror rests on.
 * Every shard's world begins as a copy of the same map, so the shard looking at this already has a
 * version of the same chunk and only has to be told what differs - but *it* works out what differs,
 * by comparing this against its own copy. The owner never has to remember what it has changed, and
 * nothing has to be logged, replayed or kept in sync.</p>
 *
 * <p>That is what makes drift a matter of size rather than of correctness. A week after the split the
 * two copies have diverged further, so the difference the viewer finds is bigger; it is never wrong.
 * The worst case is a chunk rebuilt from scratch, where the difference is the whole chunk - which is
 * no more than sending the chunk would have cost in the first place.</p>
 *
 * @param minY the world's lowest block, so the sections below can be placed at the right heights on a
 *     server whose world is a different depth
 * @param sections bottom to top, one per 16 blocks of height, none of them left out
 */
public record ChunkState(int minY, List<ChunkSectionState> sections) {

    public ChunkState {
        sections = List.copyOf(sections);
    }
}
