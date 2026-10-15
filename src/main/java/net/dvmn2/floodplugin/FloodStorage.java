package net.dvmn2.floodplugin;

import java.io.*;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Бинарное хранилище плана и прогресса одного затопления: plugins/.../floods/&lt;имя&gt;.bin (gzip).
 */
final class FloodStorage {

    private static final int MAGIC = 0x464C4431; // "FLD1"

    record Data(long[] cells, BitSet flooded) {
    }

    private FloodStorage() {
    }

    static void write(Path file, long[] cells, BitSet flooded) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                new GZIPOutputStream(Files.newOutputStream(tmp)), 1 << 16))) {
            out.writeInt(MAGIC);
            out.writeInt(cells.length);
            for (long cell : cells) {
                out.writeLong(cell);
            }
            long[] bits = flooded.toLongArray();
            out.writeInt(bits.length);
            for (long bit : bits) {
                out.writeLong(bit);
            }
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Data read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                new GZIPInputStream(Files.newInputStream(file)), 1 << 16))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("Unknown file format");
            }
            int n = in.readInt();
            if (n < 0 || n > 100_000_000) {
                throw new IOException("Corrupted cell count: " + n);
            }
            long[] cells = new long[n];
            for (int i = 0; i < n; i++) {
                cells[i] = in.readLong();
            }
            int words = in.readInt();
            if (words < 0 || words > n / 64 + 2) {
                throw new IOException("Corrupted bitset size: " + words);
            }
            long[] bits = new long[words];
            for (int i = 0; i < words; i++) {
                bits[i] = in.readLong();
            }
            return new Data(cells, BitSet.valueOf(bits));
        }
    }
}
