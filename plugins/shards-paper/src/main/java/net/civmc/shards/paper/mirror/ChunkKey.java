package net.civmc.shards.paper.mirror;

/**
 * One chunk of one world. The world is part of it because shards divide the x/z plane across every
 * world at once, so the same chunk coordinates exist in the overworld, the nether and the end and
 * belong to the same shard without holding the same blocks.
 */
public record ChunkKey(String world, int x, int z) {
}
