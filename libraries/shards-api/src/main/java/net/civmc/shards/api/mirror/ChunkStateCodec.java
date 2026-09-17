package net.civmc.shards.api.mirror;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

/**
 * Turns a {@link ChunkState} into bytes and back.
 *
 * <p>Deflated, because the uncompressed form is mostly one index repeated four thousand times - a
 * section of solid stone is 8KB of the same number. Compression is what makes shipping whole chunk
 * state affordable enough that the owner never has to track what it has changed.</p>
 *
 * <p>Written by hand rather than with a serialization library so the format is visible here and a
 * version can be added to the front of it the day it needs one.</p>
 */
public final class ChunkStateCodec {

    private static final int FORMAT_VERSION = 1;
    private static final byte SECTION_EMPTY = 0;
    private static final byte SECTION_PALETTED = 1;

    private ChunkStateCodec() {
    }

    public static byte[] toBytes(final ChunkState state) {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(bytes))) {
            out.writeInt(FORMAT_VERSION);
            out.writeInt(state.minY());
            out.writeInt(state.sections().size());
            for (final ChunkSectionState section : state.sections()) {
                writeSection(out, section);
            }
        } catch (final IOException exception) {
            // Nothing here does IO in the sense that can fail; the streams are in memory
            throw new IllegalStateException("Could not encode chunk state", exception);
        }
        return bytes.toByteArray();
    }

    public static ChunkState fromBytes(final byte[] encoded) {
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(encoded)))) {
            final int version = in.readInt();
            if (version != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported chunk state version " + version);
            }
            final int minY = in.readInt();
            final int sectionCount = in.readInt();
            final List<ChunkSectionState> sections = new ArrayList<>(sectionCount);
            for (int index = 0; index < sectionCount; index++) {
                sections.add(readSection(in));
            }
            return new ChunkState(minY, sections);
        } catch (final IOException exception) {
            throw new IllegalArgumentException("Could not decode chunk state", exception);
        }
    }

    private static void writeSection(final DataOutputStream out, final ChunkSectionState section)
        throws IOException {
        if (section.isEmpty()) {
            out.writeByte(SECTION_EMPTY);
            return;
        }
        out.writeByte(SECTION_PALETTED);
        out.writeInt(section.palette().size());
        for (final String blockData : section.palette()) {
            out.writeUTF(blockData);
        }
        for (final short index : section.indices()) {
            out.writeShort(index);
        }
    }

    private static ChunkSectionState readSection(final DataInputStream in) throws IOException {
        final byte kind = in.readByte();
        if (kind == SECTION_EMPTY) {
            return ChunkSectionState.empty();
        }
        final int paletteSize = in.readInt();
        final List<String> palette = new ArrayList<>(paletteSize);
        for (int index = 0; index < paletteSize; index++) {
            palette.add(in.readUTF());
        }
        final short[] indices = new short[ChunkSectionState.BLOCKS_PER_SECTION];
        for (int index = 0; index < indices.length; index++) {
            indices[index] = in.readShort();
        }
        return new ChunkSectionState(palette, indices);
    }
}
