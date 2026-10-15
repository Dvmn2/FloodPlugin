package net.dvmn2.floodplugin;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;

/**
 * Не даёт воде отсека вытекать за его пределы (через пробоину, окна и т.п.).
 */
public final class FloodListener implements Listener {

    private final FloodManager manager;

    public FloodListener(FloodManager manager) {
        this.manager = manager;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent event) {
        if (manager.blocksFlow(event.getBlock(), event.getToBlock())) {
            event.setCancelled(true);
        }
    }
}
