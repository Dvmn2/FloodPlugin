package net.dvmn2.floodplugin;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Одно затопление (одна лодка): пробоины, план заливки и прогресс.
 * <p>
 * План — массив позиций, отсортированный снизу вверх. Заливка идёт от начала плана, откачка — с конца,
 * поэтому вода всегда заполняет нижние блоки раньше верхних, а откачивается сверху вниз.
 */
public final class Flood {

    public enum Mode {IDLE, FLOODING, PUMPING}

    public enum Result {NONE, FLOODED_FULL, PUMPED_FULL}

    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,32}");

    private static BlockData waterData;

    public final String name;
    public final UUID worldId;
    public final Set<Long> breaches = new LinkedHashSet<>();
    public int rate;
    public Mode mode = Mode.IDLE;
    /**
     * Идёт полная откачка командой reset: по окончании план очищается.
     */
    public boolean resetting;
    /**
     * Пробоины изменились после последнего сканирования.
     */
    public boolean planDirty = true;
    public boolean dataDirty;

    FloodScan scan;
    Runnable afterScan;

    private long[] cells = new long[0];
    private BitSet flooded = new BitSet();
    private BitSet dead = new BitSet();
    private int floodedCount;
    private int fillPos;
    private int pumpPos = -1;
    private double credit;
    private final Set<Long> region = new HashSet<>();
    private int minX, maxX, minY, maxY, minZ, maxZ;

    public Flood(String name, UUID worldId, int rate) {
        this.name = name;
        this.worldId = worldId;
        this.rate = rate;
    }

    public static boolean isValidName(String name) {
        return NAME.matcher(name).matches();
    }

    // ------------------------------------------------------------------
    // Состояние
    // ------------------------------------------------------------------

    public int total() {
        return cells.length;
    }

    public int floodedCount() {
        return floodedCount;
    }

    public int percent() {
        return cells.length == 0 ? 0 : (int) ((long) floodedCount * 100L / cells.length);
    }

    /**
     * Высота самого верхнего залитого блока или null.
     */
    public Integer waterlineY() {
        int idx = flooded.previousSetBit(cells.length - 1);
        return idx < 0 ? null : Pos.y(cells[idx]);
    }

    long[] cells() {
        return cells;
    }

    BitSet floodedBits() {
        return flooded;
    }

    Set<Long> floodedSet() {
        Set<Long> set = new HashSet<>();
        for (int i = flooded.nextSetBit(0); i >= 0; i = flooded.nextSetBit(i + 1)) {
            set.add(cells[i]);
        }
        return set;
    }

    // ------------------------------------------------------------------
    // План
    // ------------------------------------------------------------------

    void applyPlan(long[] newCells, Set<Long> oldFlooded) {
        BitSet newFlooded = new BitSet(newCells.length);
        int count = 0;
        for (int i = 0; i < newCells.length; i++) {
            if (oldFlooded.contains(newCells[i])) {
                newFlooded.set(i);
                count++;
            }
        }
        this.cells = newCells;
        this.flooded = newFlooded;
        this.floodedCount = count;
        this.dead = new BitSet();
        this.fillPos = 0;
        this.pumpPos = newCells.length - 1;
        this.planDirty = false;
        this.dataDirty = true;
        rebuildRegion();
    }

    void restore(long[] savedCells, BitSet savedFlooded) {
        this.cells = savedCells;
        this.flooded = savedFlooded;
        this.floodedCount = savedFlooded.cardinality();
        this.dead = new BitSet();
        this.fillPos = 0;
        this.pumpPos = savedCells.length - 1;
        rebuildRegion();
    }

    void clearPlan() {
        cells = new long[0];
        flooded = new BitSet();
        dead = new BitSet();
        floodedCount = 0;
        fillPos = 0;
        pumpPos = -1;
        credit = 0;
        planDirty = true;
        dataDirty = true;
        rebuildRegion();
    }

    private void rebuildRegion() {
        region.clear();
        if (cells.length == 0) {
            return;
        }
        minX = minY = minZ = Integer.MAX_VALUE;
        maxX = maxY = maxZ = Integer.MIN_VALUE;
        for (long key : cells) {
            region.add(key);
            int x = Pos.x(key);
            int y = Pos.y(key);
            int z = Pos.z(key);
            minX = Math.min(minX, x);
            maxX = Math.max(maxX, x);
            minY = Math.min(minY, y);
            maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z);
            maxZ = Math.max(maxZ, z);
        }
    }

    boolean inBox(Block block) {
        if (region.isEmpty()) {
            return false;
        }
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    boolean inRegion(long key) {
        return region.contains(key);
    }

    // ------------------------------------------------------------------
    // Управление
    // ------------------------------------------------------------------

    void beginFlooding() {
        mode = Mode.FLOODING;
        resetting = false;
        fillPos = 0;
        dead.clear();
        credit = 0;
        dataDirty = true;
    }

    void beginPumping() {
        mode = Mode.PUMPING;
        pumpPos = cells.length - 1;
        credit = 0;
        dataDirty = true;
    }

    // ------------------------------------------------------------------
    // Тик
    // ------------------------------------------------------------------

    /**
     * Обрабатывает до ratePerSecond/20 блоков. Если нужный чанк не загружен — ждёт.
     */
    Result tick(World world, double ratePerSecond, int maxOps) {
        if (mode == Mode.IDLE) {
            return Result.NONE;
        }
        credit = Math.min(credit + ratePerSecond / 20.0, maxOps);
        int ops = (int) credit;
        if (ops <= 0) {
            return Result.NONE;
        }
        int done = 0;
        Result result = Result.NONE;
        while (done < ops) {
            if (mode == Mode.FLOODING) {
                int idx = nextFillIndex();
                if (idx < 0) {
                    mode = Mode.IDLE;
                    result = Result.FLOODED_FULL;
                    break;
                }
                long key = cells[idx];
                if (!world.isChunkLoaded(Pos.x(key) >> 4, Pos.z(key) >> 4)) {
                    break;
                }
                fillCell(world, idx, key);
                done++;
            } else {
                int idx = flooded.previousSetBit(pumpPos);
                if (idx < 0) {
                    mode = Mode.IDLE;
                    result = Result.PUMPED_FULL;
                    break;
                }
                long key = cells[idx];
                if (!world.isChunkLoaded(Pos.x(key) >> 4, Pos.z(key) >> 4)) {
                    break;
                }
                pumpCell(world, idx, key);
                done++;
            }
        }
        credit -= done;
        if (done > 0 || result != Result.NONE) {
            dataDirty = true;
        }
        return result;
    }

    private int nextFillIndex() {
        int n = cells.length;
        while (fillPos < n && (flooded.get(fillPos) || dead.get(fillPos))) {
            fillPos++;
        }
        return fillPos < n ? fillPos : -1;
    }

    private void markFlooded(int idx) {
        flooded.set(idx);
        floodedCount++;
    }

    private static BlockData water() {
        if (waterData == null) {
            waterData = Bukkit.createBlockData(Material.WATER);
        }
        return waterData;
    }

    private void fillCell(World world, int idx, long key) {
        Block block = world.getBlockAt(Pos.x(key), Pos.y(key), Pos.z(key));
        BlockData data = block.getBlockData();
        if (CellKind.isWaterSource(data)) {
            markFlooded(idx);
            return;
        }
        switch (CellKind.of(data)) {
            case AIR -> {
                block.setBlockData(water(), true);
                markFlooded(idx);
            }
            case WATERLOG -> {
                Waterlogged waterlogged = (Waterlogged) data;
                waterlogged.setWaterlogged(true);
                block.setBlockData(waterlogged, true);
                markFlooded(idx);
            }
            default -> {
                if (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
                    markFlooded(idx);
                } else {
                    dead.set(idx); // блок изменили (игрок поставил стену) — пропускаем
                }
            }
        }
    }

    private void pumpCell(World world, int idx, long key) {
        Block block = world.getBlockAt(Pos.x(key), Pos.y(key), Pos.z(key));
        BlockData data = block.getBlockData();
        if (data.getMaterial() == Material.WATER) {
            block.setType(Material.AIR, true);
        } else if (data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged()) {
            waterlogged.setWaterlogged(false);
            block.setBlockData(waterlogged, true);
        }
        flooded.clear(idx);
        floodedCount--;
        pumpPos = idx - 1;
        if (idx < fillPos) {
            fillPos = idx;
        }
    }
}
