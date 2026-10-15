package net.dvmn2.floodplugin;

import org.bukkit.World;

/**
 * Публичные проверки для команд.
 */
public final class FloodChecks {

    private FloodChecks() {
    }

    /**
     * Есть ли в точке или рядом с ней воздух/заливаемый блок (годится как пробоина).
     */
    public static boolean isSeedValid(World world, int x, int y, int z) {
        return FloodScan.isSeedValid(world, x, y, z);
    }
}
