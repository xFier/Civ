package net.civmc.shards.paper.mirror;

import java.util.ArrayList;
import java.util.List;
import net.civmc.shards.api.mirror.MirroredSign;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.DyeColor;
import org.bukkit.Location;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.bukkit.entity.Player;

/**
 * Reads what a chunk's signs say, and writes it back on the other side.
 *
 * <p>A sign is a block, so the mirror has always sent it - and only ever sent the board. What it says
 * is kept beside the block rather than in it and travelled nowhere, so a neighbour's town arrived with
 * every sign in it blank. On CivMC that is most of what there is to read at a border.</p>
 *
 * <p>Read on the main thread in the same step as the chunk snapshot and the still entities, for the
 * same reason: it is a copy, and copying is all the main thread should be asked to do for somebody
 * else's rendering.</p>
 */
public final class Signs {

    private static final int LINES = 4;

    private Signs() {
    }

    /**
     * @return what every sign in this chunk says, or an empty list. Main thread
     */
    public static List<MirroredSign> in(final Chunk chunk) {
        final List<MirroredSign> signs = new ArrayList<>();
        // Without a snapshot these are the live block states, which is what is wanted: this is read on
        // the main thread and copied out of immediately
        for (final BlockState state : chunk.getTileEntities(false)) {
            if (state instanceof Sign sign) {
                signs.add(describe(sign));
            }
        }
        return signs;
    }

    private static MirroredSign describe(final Sign sign) {
        final SignSide front = sign.getSide(Side.FRONT);
        final SignSide back = sign.getSide(Side.BACK);
        return new MirroredSign(sign.getX(), sign.getY(), sign.getZ(),
            sign.getBlockData().getAsString(), lines(front), lines(back),
            front.getColor() == null ? "" : front.getColor().name(),
            back.getColor() == null ? "" : back.getColor().name(),
            front.isGlowingText(), back.isGlowingText());
    }

    /**
     * The game's own JSON for each line rather than plain text, so a coloured shop sign arrives
     * coloured. Both shards run the same version, which is what makes the same reader right at both
     * ends - the same argument the item in a frame travels on.
     */
    private static List<String> lines(final SignSide side) {
        final List<String> lines = new ArrayList<>(LINES);
        for (final Component line : side.lines()) {
            lines.add(GsonComponentSerializer.gson().serialize(line));
        }
        return lines;
    }

    /**
     * Writes a neighbour's signs onto the boards a player has just been shown.
     *
     * <p>After the blocks and never before them: this changes what a sign says and not what is there,
     * so a board that has not arrived yet has nothing to write on. Nothing is placed in this world by
     * any of it - the same construction as everything else in the mirror, which is what makes it
     * impossible to take a neighbour's sign rather than merely forbidden.</p>
     *
     * <p>Needs no packet library, unlike the frames and the people: the API can send one client a
     * block state that is not there.</p>
     */
    public static void draw(final Player viewer, final List<MirroredSign> signs) {
        for (final MirroredSign sign : signs) {
            try {
                write(viewer, sign);
            } catch (final RuntimeException unreadable) {
                // One sign this server cannot make sense of is not worth the rest of the street
                viewer.getServer().getLogger().finest(() -> "Could not draw a mirrored sign: " + unreadable);
            }
        }
    }

    private static void write(final Player viewer, final MirroredSign sign) {
        final BlockState state = Bukkit.createBlockData(sign.blockData()).createBlockState();
        if (!(state instanceof Sign board)) {
            return;
        }
        write(board.getSide(Side.FRONT), sign.front(), sign.frontColor(), sign.frontGlowing());
        write(board.getSide(Side.BACK), sign.back(), sign.backColor(), sign.backGlowing());
        viewer.sendBlockUpdate(new Location(viewer.getWorld(), sign.x(), sign.y(), sign.z()), board);
    }

    private static void write(final SignSide side, final List<String> lines, final String color,
                              final boolean glowing) {
        for (int line = 0; line < Math.min(LINES, lines.size()); line++) {
            side.line(line, GsonComponentSerializer.gson().deserialize(lines.get(line)));
        }
        if (!color.isEmpty()) {
            side.setColor(DyeColor.valueOf(color));
        }
        side.setGlowingText(glowing);
    }
}
