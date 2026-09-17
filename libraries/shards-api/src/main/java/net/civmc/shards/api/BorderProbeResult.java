package net.civmc.shards.api;

import java.util.Objects;

/**
 * What one block turned out to be, in answer to a {@link BorderProbeRequest}.
 *
 * <p>Carries its own coordinates rather than relying on the order of the request. The two are the
 * same list today, but a border is drawn from these and a picture silently offset by one entry is
 * exactly the kind of wrong that looks right.</p>
 *
 * @param shardName who owns it, null when nobody does
 */
public record BorderProbeResult(int x, int z, BorderProbeStatus status, String shardName) {

    public BorderProbeResult {
        Objects.requireNonNull(status, "status");
    }
}
