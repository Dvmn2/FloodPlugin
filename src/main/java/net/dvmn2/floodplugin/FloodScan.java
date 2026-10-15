package net.dvmn2.floodplugin;

import org.bukkit.World;

import java.util.*;

/**
 * Пошаговый (с лимитом времени на тик) flood fill отсека от точек пробоин.
 * Идёт по воздуху, неполным заливаемым блокам и открытым дверям; незагруженные чанки — стена.
 * Блоки, уже залитые этим затоплением, считаются проходимыми и входят в план.
 */
final class FloodScan {

    enum Failure {NONE, NO_SEED, LIMIT}

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    private final World world;
    private final int limit;
    private final int minY;
    private final int maxY;
    private final Set<Long> oldFlooded;
    private final LongList queue = new LongList();
    private final BitSet included = new BitSet();
    private final HashSet<Long> seen = new HashSet<>();
    private int head;
    private int includedCount;
    private Failure failure = Failure.NONE;
    private boolean finished;

    FloodScan(World world, Collection<Long> breaches, Set<Long> oldFlooded, int limit) {
        this.world = world;
        this.limit = limit;
        this.minY = world.getMinHeight();
        this.maxY = world.getMaxHeight();
        this.oldFlooded = oldFlooded;
        for (long breach : breaches) {
            seed(Pos.x(breach), Pos.y(breach), Pos.z(breach));
        }
        if (queue.size() == 0) {
            failure = Failure.NO_SEED;
            finished = true;
        }
    }

    /**
     * Есть ли в точке или рядом с ней проходимый блок (для проверки пробоины при добавлении).
     */
    static boolean isSeedValid(World world, int x, int y, int z) {
        if (CellKind.of(world.getBlockData(x, y, z)) != CellKind.WALL) {
            return true;
        }
        for (int i = 0; i < 6; i++) {
            if (CellKind.of(world.getBlockData(x + DX[i], y + DY[i], z + DZ[i])) != CellKind.WALL) {
                return true;
            }
        }
        return false;
    }

    Failure failure() {
        return failure;
    }

    Set<Long> oldFlooded() {
        return oldFlooded;
    }

    private void seed(int x, int y, int z) {
        if (trySeed(x, y, z)) {
            return;
        }
        for (int i = 0; i < 6; i++) {
            if (trySeed(x + DX[i], y + DY[i], z + DZ[i])) {
                return;
            }
        }
    }

    private boolean trySeed(int x, int y, int z) {
        long key = Pos.pack(x, y, z);
        if (seen.contains(key)) {
            return true;
        }
        CellKind kind = kind(key, x, y, z);
        if (kind == CellKind.WALL) {
            return false;
        }
        enqueue(key, kind);
        return true;
    }

    private CellKind kind(long key, int x, int y, int z) {
        if (y < minY || y >= maxY || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return CellKind.WALL;
        }
        if (oldFlooded.contains(key)) {
            return CellKind.AIR;
        }
        return CellKind.of(world.getBlockData(x, y, z));
    }

    private void enqueue(long key, CellKind kind) {
        seen.add(key);
        queue.add(key);
        if (kind.fillable()) {
            included.set(queue.size() - 1);
            includedCount++;
            if (includedCount > limit) {
                failure = Failure.LIMIT;
            }
        }
    }

    /**
     * Выполняет работу не дольше budgetNanos. Возвращает true, когда сканирование завершено.
     */
    boolean step(long budgetNanos) {
        if (finished) {
            return true;
        }
        long deadline = System.nanoTime() + budgetNanos;
        int counter = 0;
        while (head < queue.size()) {
            long current = queue.get(head++);
            int cx = Pos.x(current);
            int cy = Pos.y(current);
            int cz = Pos.z(current);
            for (int i = 0; i < 6; i++) {
                int nx = cx + DX[i];
                int ny = cy + DY[i];
                int nz = cz + DZ[i];
                long key = Pos.pack(nx, ny, nz);
                if (!seen.add(key)) {
                    continue;
                }
                CellKind kind = kind(key, nx, ny, nz);
                if (kind == CellKind.WALL) {
                    continue;
                }
                queue.add(key);
                if (kind.fillable()) {
                    included.set(queue.size() - 1);
                    includedCount++;
                    if (includedCount > limit) {
                        failure = Failure.LIMIT;
                        finished = true;
                        return true;
                    }
                }
            }
            if ((++counter & 31) == 0 && System.nanoTime() >= deadline) {
                return false;
            }
        }
        finished = true;
        return true;
    }

    /**
     * Итоговый план: позиции, отсортированные по высоте (снизу вверх), внутри слоя — по удалённости
     * от пробоин. Ранее залитые, но недостижимые блоки сохраняются в плане (чтобы их можно было откачать).
     */
    long[] result() {
        int extra = 0;
        for (long key : oldFlooded) {
            if (!seen.contains(key)) {
                extra++;
            }
        }
        long[] raw = new long[includedCount + extra];
        int n = 0;
        for (int i = included.nextSetBit(0); i >= 0; i = included.nextSetBit(i + 1)) {
            raw[n++] = queue.get(i);
        }
        for (long key : oldFlooded) {
            if (!seen.contains(key)) {
                raw[n++] = key;
            }
        }
        long[] sortKeys = new long[n];
        for (int i = 0; i < n; i++) {
            sortKeys[i] = ((long) (Pos.y(raw[i]) + 2048) << 40) | i;
        }
        Arrays.sort(sortKeys);
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = raw[(int) (sortKeys[i] & 0xFFFFFFFFFFL)];
        }
        return out;
    }
}
