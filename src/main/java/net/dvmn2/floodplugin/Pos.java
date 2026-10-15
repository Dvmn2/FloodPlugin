package net.dvmn2.floodplugin;

/**
 * Упаковка координат блока в long: x — 26 бит, z — 26 бит, y — 12 бит (со знаком).
 */
public final class Pos {

    private Pos() {
    }

    public static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (long) (y & 0xFFF);
    }

    public static int x(long p) {
        return (int) (p >> 38);
    }

    public static int y(long p) {
        return (int) ((p << 52) >> 52);
    }

    public static int z(long p) {
        return (int) ((p << 26) >> 38);
    }
}
