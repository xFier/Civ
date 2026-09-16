package net.civmc.shards.velocity.database;

import java.util.UUID;
import net.civmc.shards.api.PlayerLocation;

/**
 * A row of {@code shard_player_data}. Every column except the primary key is nullable: a freshly
 * claimed player has only {@code owning_server_uuid} set, and has no payload or location until the
 * owning server writes one back.
 */
public record PlayerDataRow(byte[] payload, UUID owningServerUuid, String world, Double x, Double y, Double z) {

    /**
     * @return null when this row has never been written back, so has no location yet
     */
    public PlayerLocation location() {
        if (this.world == null || this.x == null || this.y == null || this.z == null) {
            return null;
        }
        return new PlayerLocation(this.world, this.x, this.y, this.z);
    }
}
