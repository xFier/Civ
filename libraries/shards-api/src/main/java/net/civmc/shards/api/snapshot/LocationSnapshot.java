package net.civmc.shards.api.snapshot;

import java.util.Objects;

/**
 * A position, carried by value because the destination server has no handle on the source's worlds.
 */
public record LocationSnapshot(String world, double x, double y, double z, float yaw, float pitch) {

    public LocationSnapshot {
        Objects.requireNonNull(world, "world");
    }
}
