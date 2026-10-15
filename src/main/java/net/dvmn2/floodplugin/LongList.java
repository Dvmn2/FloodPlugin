package net.dvmn2.floodplugin;

import java.util.Arrays;

/**
 * Минимальный растущий массив long без упаковки в Long.
 */
final class LongList {

    private long[] data = new long[256];
    private int size;

    void add(long value) {
        if (size == data.length) {
            data = Arrays.copyOf(data, size * 2);
        }
        data[size++] = value;
    }

    long get(int index) {
        return data[index];
    }

    int size() {
        return size;
    }
}
