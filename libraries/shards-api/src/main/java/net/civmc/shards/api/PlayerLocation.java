package net.civmc.shards.api;

/**
 * Where a player was when their data was last written back.
 */
public record PlayerLocation(String world, double x, double y, double z) {
}
