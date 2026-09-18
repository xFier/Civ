package net.civmc.shards.api.mirror;

import java.util.List;
import java.util.Objects;

/**
 * What one of a shard's signs says, so another shard can draw it rather than a blank board.
 *
 * <p>A sign is a block and the mirror has always sent it - but only as a block. The text is not part
 * of a block state; it is kept beside it, and none of it travelled. So a neighbour's town got its
 * walls, its shops got their frames, and every sign in the place was blank. On CivMC that is most of
 * what a border looks at: shop prices, names over doors, warnings at a gate.</p>
 *
 * <p>Both faces, because a sign has had two of them for years and the one you read is the one you are
 * standing in front of - and a border is exactly the place where somebody is standing on the wrong
 * side of it.</p>
 *
 * <p>Lines travel as the game's own JSON for them rather than as plain text, so colours, translations
 * and the rest arrive as written. Both shards run the same version, so the same reader is at both
 * ends - the same argument the items in a frame travel on.</p>
 *
 * @param blockData the sign block itself, so the receiver can build the right kind of sign to write on
 *     without depending on its own copy of that block being a sign at all
 * @param front the four lines of the front, each one serialized JSON, empty strings for blank lines
 * @param back the four lines of the back, in the same form
 * @param frontColor and {@code backColor} the dye colour of each face, a {@code DyeColor} name
 * @param frontGlowing and {@code backGlowing} whether each face has been lit with glow ink
 */
public record MirroredSign(int x, int y, int z, String blockData, List<String> front, List<String> back,
                           String frontColor, String backColor, boolean frontGlowing,
                           boolean backGlowing) {

    public MirroredSign {
        Objects.requireNonNull(blockData, "blockData");
        front = front == null ? List.of() : List.copyOf(front);
        back = back == null ? List.of() : List.copyOf(back);
        frontColor = frontColor == null ? "" : frontColor;
        backColor = backColor == null ? "" : backColor;
    }
}
