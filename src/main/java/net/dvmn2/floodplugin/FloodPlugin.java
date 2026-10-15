package net.dvmn2.floodplugin;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.dvmn2.floodplugin.commands.FloodCommand;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public final class FloodPlugin extends JavaPlugin {

    private FloodManager manager;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }
        saveDefaultConfig();
        Lang.setLanguage(getConfig().getString("settings.language", "auto"));

        manager = new FloodManager(this);
        manager.reloadSettings();
        manager.load();

        getServer().getPluginManager().registerEvents(new FloodListener(manager), this);
        Bukkit.getScheduler().runTaskTimer(this, manager::tick, 1L, 1L);

        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
                event.registrar().register(
                        new FloodCommand(this).build().build(),
                        "Симуляция затопления подводной лодки"
                ));

        getLogger().info("FloodPlugin включён. Затоплений: " + manager.names().size()
                + ". Команда: /flood create|delete|list|status|breach|start|pump|pause|stop|rate|reset|reload");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.saveAll();
        }
    }

    public void reloadAll() {
        reloadConfig();
        Lang.setLanguage(getConfig().getString("settings.language", "auto"));
        manager.reloadSettings();
    }

    public FloodManager getManager() {
        return manager;
    }
}
