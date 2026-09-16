package net.civmc.shards.api.snapshot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;

/**
 * Turns a snapshot into the bytes stored in the payload column and back.
 *
 * <p>JSON rather than a binary format because the payload is the thing an operator ends up reading
 * when a player reports losing something, and because adding a field must not invalidate the rows
 * already written.</p>
 */
public final class PlayerSnapshotCodec {

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlayerSnapshotCodec() {
    }

    public static byte[] toBytes(final PlayerSnapshot snapshot) {
        return GSON.toJson(snapshot).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * @return the snapshot, or null if {@code payload} is null or empty - which is what a player who
     *     has a row but has never been written back looks like
     */
    public static PlayerSnapshot fromBytes(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return null;
        }
        return GSON.fromJson(new String(payload, StandardCharsets.UTF_8), PlayerSnapshot.class);
    }
}
