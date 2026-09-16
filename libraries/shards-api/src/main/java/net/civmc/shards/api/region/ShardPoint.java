package net.civmc.shards.api.region;

import org.spongepowered.configurate.objectmapping.ConfigSerializable;

/**
 * A corner of a {@link ShardRegion}.
 *
 * <p>Corners sit on the grid lines <em>between</em> blocks rather than on block centres, so two
 * neighbouring shards can name the same coordinate and meet without a gap or a shared block. See
 * {@link ShardRegion#containsBlock}.</p>
 */
@ConfigSerializable
public record ShardPoint(int x, int z) {
}
