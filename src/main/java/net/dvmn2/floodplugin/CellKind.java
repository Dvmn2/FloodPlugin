package net.dvmn2.floodplugin;

import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.TrapDoor;

/**
 * Как блок ведёт себя для затопления.
 * <ul>
 *   <li>AIR — воздух или текущая (не источник) вода: заменяется блоком воды-источника;</li>
 *   <li>WATERLOG — неполный блок, который можно залить (waterlogged=true);</li>
 *   <li>PASS — вода проходит сквозь блок (открытая дверь/калитка), но сам он не заливается;</li>
 *   <li>WALL — преграда.</li>
 * </ul>
 */
public enum CellKind {
    AIR, WATERLOG, PASS, WALL;

    public boolean fillable() {
        return this == AIR || this == WATERLOG;
    }

    public static CellKind of(BlockData data) {
        Material type = data.getMaterial();
        if (type.isAir()) {
            return AIR;
        }
        if (type == Material.WATER) {
            // Источник воды — преграда для поиска отсека; текущая вода — как воздух.
            return data instanceof Levelled levelled && levelled.getLevel() > 0 ? AIR : WALL;
        }
        if (data instanceof Waterlogged waterlogged) {
            if (waterlogged.isWaterlogged()) {
                return WALL;
            }
            if (data instanceof Slab slab && slab.getType() == Slab.Type.DOUBLE) {
                return WALL;
            }
            if (data instanceof TrapDoor trapDoor && !trapDoor.isOpen()) {
                return WALL; // закрытый люк — преграда
            }
            return WATERLOG;
        }
        if (data instanceof Openable openable && openable.isOpen()) {
            return PASS;
        }
        return WALL;
    }

    /**
     * Блок — источник воды (уровень 0).
     */
    public static boolean isWaterSource(BlockData data) {
        return data.getMaterial() == Material.WATER
                && data instanceof Levelled levelled && levelled.getLevel() == 0;
    }
}
