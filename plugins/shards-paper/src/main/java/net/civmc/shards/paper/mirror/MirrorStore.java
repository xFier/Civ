package net.civmc.shards.paper.mirror;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Keeps what the neighbours have told us, so it is not asked for again from nothing.
 *
 * <p>Until this, everything a neighbour said lived only in memory. A restart threw all of it away and
 * every chunk along every border was read again from its owner - and worse, <strong>a neighbour that
 * is down leaves a hole in the map</strong>: with nothing to draw, a player at the border sees this
 * server's own untouched copy, which is empty hills where there is a city. The one time the mirror is
 * most needed is the one time it had nothing.</p>
 *
 * <p>What is saved is the <em>difference</em> from this server's own copy, not the neighbour's chunk.
 * It is a fraction of the size - tens of blocks against sixteen thousand - and it is the thing that
 * gets drawn, so a saved chunk goes on screen without reading anything or comparing anything. The
 * price is that it only means anything against this server's own copy of that ground - so it holds
 * only for as long as that copy does not move, which is what {@code ShardBorderListener} guarantees by
 * refusing every block change past the border.</p>
 *
 * <p><strong>Still not a catch-up log.</strong> Nothing here is replayed and nothing is trusted. What
 * is loaded is a <em>starting picture</em>, shown at once and marked to be read again the moment
 * anybody looks at it - so it is only ever as wrong as the last second, and only for as long as the
 * owner cannot be reached. The announcement number is saved with it, so an announcement arriving after
 * a restart is still checked for a gap rather than accepted blindly (see {@link ChunkRevisions}).</p>
 *
 * <p>One file, rewritten whole. That is a choice worth knowing about: it is fine because only chunks
 * near a border are ever fetched and only those with something to draw are saved, which on the rig is
 * tens of blocks each. A border long enough for this to be a big file wants writing per region
 * instead, and the log line says how big it got so that is visible before it is a problem.</p>
 */
public final class MirrorStore {

    private static final int FORMAT = 1;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private final Path file;
    private final Logger logger;

    public MirrorStore(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /**
     * One chunk as it was last seen, in the shape it is written in.
     *
     * @param blocks what the owner has where this server's own copy has something else
     * @param publisherId which run of the owning server numbered the announcements this counts
     * @param revision the last announcement about this chunk accounted for here
     */
    public record Saved(String world, int x, int z, String publisherId, long revision,
                        List<SavedBlock> blocks) {
    }

    /**
     * Written out as block data strings rather than anything parsed, so a file from an older version of
     * the game is readable rather than a failure - a block that no longer exists is refused one block
     * at a time when it is read back.
     */
    public record SavedBlock(int x, int y, int z, String blockData) {
    }

    private record Saves(int format, List<Saved> chunks) {
    }

    /**
     * Reads what was saved last time. An unreadable or missing file is not a failure: the mirror
     * simply starts from nothing, which is what it did before this existed.
     */
    public List<Saved> load() {
        if (!Files.isRegularFile(this.file)) {
            return List.of();
        }
        final long startedAt = System.nanoTime();
        try (Reader reader = new InputStreamReader(
            new GZIPInputStream(Files.newInputStream(this.file)), StandardCharsets.UTF_8)) {
            final Saves saves = GSON.fromJson(reader, Saves.class);
            if (saves == null || saves.chunks() == null) {
                return List.of();
            }
            if (saves.format() != FORMAT) {
                this.logger.warning("Ignoring the saved mirror: it is format " + saves.format()
                    + " and this server writes " + FORMAT + ". Every chunk will be read from its owner");
                return List.of();
            }
            final List<Saved> chunks = new ArrayList<>(saves.chunks().size());
            for (final Saved saved : saves.chunks()) {
                if (saved != null && saved.world() != null && saved.blocks() != null) {
                    chunks.add(saved);
                }
            }
            this.logger.info("Loaded " + chunks.size() + " mirrored chunk(s) from the last run in "
                + (System.nanoTime() - startedAt) / 1_000_000L + "ms; each will be read from its owner "
                + "again the first time anybody looks at it");
            return chunks;
        } catch (final IOException | RuntimeException unreadable) {
            this.logger.log(Level.WARNING, "Could not read the saved mirror; starting from nothing",
                unreadable);
            return List.of();
        }
    }

    /**
     * Writes the lot. Off the main thread - the caller takes the copy.
     *
     * <p>To a temporary file and then moved over the real one, so a server killed part-way through
     * leaves the previous save intact rather than half a file. Half a file loads as nothing, which is
     * safe, but it also throws away a save that was perfectly good.</p>
     */
    public void save(final List<Saved> chunks) {
        final long startedAt = System.nanoTime();
        final Path temporary = this.file.resolveSibling(this.file.getFileName() + ".writing");
        try {
            Files.createDirectories(this.file.getParent());
            try (Writer writer = new OutputStreamWriter(
                new GZIPOutputStream(Files.newOutputStream(temporary)), StandardCharsets.UTF_8)) {
                GSON.toJson(new Saves(FORMAT, chunks), writer);
            }
            Files.move(temporary, this.file, StandardCopyOption.REPLACE_EXISTING);
            this.logger.fine(() -> "Saved " + chunks.size() + " mirrored chunk(s), "
                + sizeOnDisk() / 1024L + "KB, in " + (System.nanoTime() - startedAt) / 1_000_000L + "ms");
        } catch (final IOException | RuntimeException failure) {
            this.logger.log(Level.WARNING, "Could not save the mirror; the next run will read every "
                + "chunk from its owner", failure);
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException ignored) {
                // Nothing useful to do about it, and the next save overwrites it anyway
            }
        }
    }

    private long sizeOnDisk() {
        try {
            return Files.size(this.file);
        } catch (final IOException unknown) {
            return 0L;
        }
    }
}
