package net.dvmn2.floodplugin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;

/**
 * Хранит все затопления, ведёт их тики, сканирование и сохранение.
 * <pre>
 * plugins/FloodPlugin/floods.yml        — пробоины, скорость, состояние
 * plugins/FloodPlugin/floods/&lt;имя&gt;.bin — план заливки и прогресс
 * </pre>
 */
public final class FloodManager {

    private final FloodPlugin plugin;
    private final File metaFile;
    private final Path dataDir;
    private final Map<String, Flood> floods = new LinkedHashMap<>();
    private final Object ioLock = new Object();

    private int defaultRate;
    private int maxRate;
    private int maxRegionBlocks;
    private double scanMsPerTick;
    private int autosaveSeconds;
    private boolean resumeOnStart;
    private int autosaveTaskId = -1;

    public FloodManager(FloodPlugin plugin) {
        this.plugin = plugin;
        this.metaFile = new File(plugin.getDataFolder(), "floods.yml");
        this.dataDir = new File(plugin.getDataFolder(), "floods").toPath();
    }

    // ------------------------------------------------------------------
    // Настройки
    // ------------------------------------------------------------------

    public void reloadSettings() {
        var cfg = plugin.getConfig();
        maxRate = Math.max(1, cfg.getInt("settings.max-rate", 2000));
        defaultRate = Math.max(1, Math.min(maxRate, cfg.getInt("settings.default-rate", 20)));
        maxRegionBlocks = Math.max(1, cfg.getInt("settings.max-region-blocks", 150000));
        scanMsPerTick = Math.max(0.1, cfg.getDouble("settings.scan-ms-per-tick", 3.0));
        autosaveSeconds = Math.max(5, cfg.getInt("settings.autosave-seconds", 30));
        resumeOnStart = cfg.getBoolean("settings.resume-on-start", true);

        if (autosaveTaskId != -1) {
            Bukkit.getScheduler().cancelTask(autosaveTaskId);
        }
        long period = autosaveSeconds * 20L;
        autosaveTaskId = Bukkit.getScheduler().runTaskTimer(plugin, this::autosave, period, period).getTaskId();
    }

    public int maxRate() {
        return maxRate;
    }

    public int defaultRate() {
        return defaultRate;
    }

    public int maxRegionBlocks() {
        return maxRegionBlocks;
    }

    // ------------------------------------------------------------------
    // Доступ
    // ------------------------------------------------------------------

    public Flood get(String name) {
        return floods.get(name);
    }

    public List<String> names() {
        return new ArrayList<>(floods.keySet());
    }

    public List<Flood> all() {
        return new ArrayList<>(floods.values());
    }

    public Flood create(String name, World world) {
        Flood flood = new Flood(name, world.getUID(), defaultRate);
        floods.put(name, flood);
        saveMeta();
        return flood;
    }

    public void delete(Flood flood) {
        cancelScan(flood);
        floods.remove(flood.name);
        saveMeta();
        synchronized (ioLock) {
            try {
                Files.deleteIfExists(dataFile(flood.name));
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not delete data of " + flood.name, ex);
            }
        }
    }

    public String worldName(Flood flood) {
        World world = Bukkit.getWorld(flood.worldId);
        return world != null ? world.getName() : flood.worldId.toString();
    }

    public String modeText(Flood flood, CommandSender sender) {
        if (flood.scan != null) {
            return Lang.get(Lang.Key.MODE_SCANNING, sender);
        }
        return switch (flood.mode) {
            case IDLE -> Lang.get(Lang.Key.MODE_IDLE, sender);
            case FLOODING -> Lang.get(Lang.Key.MODE_FLOODING, sender);
            case PUMPING -> Lang.get(Lang.Key.MODE_PUMPING, sender);
        };
    }

    // ------------------------------------------------------------------
    // Операции
    // ------------------------------------------------------------------

    /**
     * Добавляет/убирает пробоину. Возвращает false, если ничего не изменилось.
     */
    public boolean addBreach(Flood flood, int x, int y, int z) {
        if (!flood.breaches.add(Pos.pack(x, y, z))) {
            return false;
        }
        breachesChanged(flood);
        return true;
    }

    public boolean removeBreach(Flood flood, int x, int y, int z) {
        if (!flood.breaches.remove(Pos.pack(x, y, z))) {
            return false;
        }
        breachesChanged(flood);
        return true;
    }

    private void breachesChanged(Flood flood) {
        flood.planDirty = true;
        if (flood.mode == Flood.Mode.FLOODING) {
            // Затопление идёт — пересканируем отсек и продолжим с учётом новых пробоин.
            startFlooding(flood);
        } else {
            saveMeta();
        }
    }

    /**
     * Запускает затопление; при необходимости сначала сканирует отсек.
     */
    public void startFlooding(Flood flood) {
        if (flood.breaches.isEmpty()) {
            pause(flood);
            return;
        }
        if (flood.planDirty || flood.total() == 0) {
            if (!beginScan(flood, () -> {
                flood.beginFlooding();
                saveMeta();
            })) {
                return;
            }
            flood.mode = Flood.Mode.FLOODING; // отображается как «сканирование», пока flood.scan != null
        } else {
            flood.beginFlooding();
        }
        saveMeta();
    }

    public void startPumping(Flood flood) {
        flood.beginPumping();
        saveMeta();
    }

    public void pause(Flood flood) {
        cancelScan(flood);
        flood.mode = Flood.Mode.IDLE;
        flood.resetting = false;
        flood.dataDirty = true;
        saveMeta();
    }

    /**
     * Полная откачка на максимальной скорости, затем очистка плана.
     */
    public void reset(Flood flood) {
        cancelScan(flood);
        if (flood.floodedCount() == 0) {
            flood.clearPlan();
            flood.mode = Flood.Mode.IDLE;
            flood.resetting = false;
        } else {
            flood.resetting = true;
            flood.beginPumping();
        }
        saveMeta();
    }

    public void setRate(Flood flood, int rate) {
        flood.rate = rate;
        saveMeta();
    }

    private void cancelScan(Flood flood) {
        flood.scan = null;
        flood.afterScan = null;
    }

    /**
     * @return false, если скан запустить не удалось (мир не загружен).
     */
    private boolean beginScan(Flood flood, Runnable after) {
        World world = Bukkit.getWorld(flood.worldId);
        if (world == null) {
            return false;
        }
        flood.scan = new FloodScan(world, flood.breaches, flood.floodedSet(), maxRegionBlocks);
        flood.afterScan = after;
        return true;
    }

    public boolean isScanning(Flood flood) {
        return flood.scan != null;
    }

    // ------------------------------------------------------------------
    // Тик
    // ------------------------------------------------------------------

    public void tick() {
        int scans = 0;
        for (Flood flood : floods.values()) {
            if (flood.scan != null) {
                scans++;
            }
        }
        long scanBudget = scans == 0 ? 0 : (long) (scanMsPerTick * 1_000_000.0 / scans);
        int maxOps = Math.max(1, (int) Math.ceil(maxRate / 20.0));

        for (Flood flood : floods.values()) {
            if (flood.scan != null) {
                if (flood.scan.step(scanBudget)) {
                    finishScan(flood);
                }
                continue;
            }
            if (flood.mode == Flood.Mode.IDLE) {
                continue;
            }
            World world = Bukkit.getWorld(flood.worldId);
            if (world == null) {
                continue;
            }
            double rate = flood.resetting ? maxRate : Math.min(flood.rate, maxRate);
            Flood.Result result = flood.tick(world, rate, maxOps);
            switch (result) {
                case FLOODED_FULL -> {
                    broadcast(Lang.Key.FLOODED_FULL, flood.name, flood.floodedCount());
                    saveMeta();
                }
                case PUMPED_FULL -> {
                    if (flood.resetting) {
                        flood.resetting = false;
                        flood.clearPlan();
                        broadcast(Lang.Key.RESET_DONE, flood.name);
                    } else {
                        broadcast(Lang.Key.PUMPED_FULL, flood.name);
                    }
                    saveMeta();
                }
                default -> {
                }
            }
        }
    }

    private void finishScan(Flood flood) {
        FloodScan scan = flood.scan;
        Runnable after = flood.afterScan;
        flood.scan = null;
        flood.afterScan = null;

        switch (scan.failure()) {
            case LIMIT -> {
                flood.mode = Flood.Mode.IDLE;
                broadcast(Lang.Key.SCAN_FAIL_LIMIT, flood.name, maxRegionBlocks);
                saveMeta();
                return;
            }
            case NO_SEED -> {
                flood.mode = Flood.Mode.IDLE;
                broadcast(Lang.Key.SCAN_FAIL_NO_SEED, flood.name);
                saveMeta();
                return;
            }
            default -> {
            }
        }
        flood.applyPlan(scan.result(), scan.oldFlooded());
        broadcast(Lang.Key.SCAN_DONE, flood.name, flood.total());
        if (after != null) {
            after.run();
        }
    }

    // ------------------------------------------------------------------
    // Защита от утечки воды наружу
    // ------------------------------------------------------------------

    /**
     * Запрещает воде из отсека растекаться в блоки за пределами отсека.
     */
    public boolean blocksFlow(Block from, Block to) {
        if (floods.isEmpty()) {
            return false;
        }
        UUID worldId = from.getWorld().getUID();
        long fromKey = Pos.pack(from.getX(), from.getY(), from.getZ());
        long toKey = Pos.pack(to.getX(), to.getY(), to.getZ());
        for (Flood flood : floods.values()) {
            if (!flood.worldId.equals(worldId) || !flood.inBox(from)) {
                continue;
            }
            if (flood.inRegion(fromKey) && !flood.inRegion(toKey)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Сообщения
    // ------------------------------------------------------------------

    public void broadcast(Lang.Key key, Object... args) {
        CommandSender console = Bukkit.getConsoleSender();
        console.sendMessage(Component.text(Lang.get(key, console, args), NamedTextColor.AQUA));
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.hasPermission("flood.admin")) {
                player.sendMessage(Component.text(Lang.get(key, player, args), NamedTextColor.AQUA));
            }
        }
    }

    // ------------------------------------------------------------------
    // Сохранение
    // ------------------------------------------------------------------

    private Path dataFile(String name) {
        return dataDir.resolve(name + ".bin");
    }

    public void load() {
        floods.clear();
        if (!metaFile.exists()) {
            return;
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(metaFile);
        ConfigurationSection root = cfg.getConfigurationSection("floods");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            if (section == null || !Flood.isValidName(name)) {
                continue;
            }
            UUID worldId;
            try {
                worldId = UUID.fromString(section.getString("world", ""));
            } catch (IllegalArgumentException ex) {
                plugin.getLogger().warning("Некорректный UUID мира у затопления " + name);
                continue;
            }
            Flood flood = new Flood(name, worldId, Math.max(1, Math.min(maxRate, section.getInt("rate", defaultRate))));
            for (String text : section.getStringList("breaches")) {
                String[] parts = text.split(",");
                if (parts.length != 3) {
                    continue;
                }
                try {
                    flood.breaches.add(Pos.pack(Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())));
                } catch (NumberFormatException ignored) {
                    // пропускаем битую запись
                }
            }
            Path file = dataFile(name);
            if (Files.exists(file)) {
                try {
                    FloodStorage.Data data = FloodStorage.read(file);
                    flood.restore(data.cells(), data.flooded());
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.WARNING, "Не удалось прочитать данные затопления " + name, ex);
                }
            }
            flood.planDirty = section.getBoolean("plan-dirty", flood.total() == 0);
            if (resumeOnStart) {
                try {
                    flood.mode = Flood.Mode.valueOf(section.getString("mode", "IDLE"));
                } catch (IllegalArgumentException ex) {
                    flood.mode = Flood.Mode.IDLE;
                }
                flood.resetting = section.getBoolean("resetting", false);
                if (flood.total() == 0) {
                    flood.mode = Flood.Mode.IDLE;
                } else if (flood.mode == Flood.Mode.PUMPING) {
                    flood.beginPumping();
                } else if (flood.mode == Flood.Mode.FLOODING) {
                    flood.beginFlooding();
                }
            }
            flood.dataDirty = false;
            floods.put(name, flood);
        }
    }

    public void saveMeta() {
        YamlConfiguration cfg = new YamlConfiguration();
        for (Flood flood : floods.values()) {
            String path = "floods." + flood.name;
            cfg.set(path + ".world", flood.worldId.toString());
            cfg.set(path + ".rate", flood.rate);
            cfg.set(path + ".mode", flood.mode.name());
            cfg.set(path + ".resetting", flood.resetting);
            cfg.set(path + ".plan-dirty", flood.planDirty);
            List<String> breaches = new ArrayList<>();
            for (long key : flood.breaches) {
                breaches.add(Pos.x(key) + "," + Pos.y(key) + "," + Pos.z(key));
            }
            cfg.set(path + ".breaches", breaches);
        }
        synchronized (ioLock) {
            try {
                plugin.getDataFolder().mkdirs();
                cfg.save(metaFile);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось сохранить floods.yml", ex);
            }
        }
    }

    /**
     * Асинхронно сохраняет планы тех затоплений, чей прогресс изменился.
     */
    private void autosave() {
        for (Flood flood : floods.values()) {
            if (!flood.dataDirty) {
                continue;
            }
            flood.dataDirty = false;
            long[] cells = flood.cells();
            BitSet bits = (BitSet) flood.floodedBits().clone();
            Path file = dataFile(flood.name);
            String name = flood.name;
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                synchronized (ioLock) {
                    try {
                        FloodStorage.write(file, cells, bits);
                    } catch (IOException ex) {
                        plugin.getLogger().log(Level.WARNING, "Не удалось сохранить данные затопления " + name, ex);
                    }
                }
            });
        }
    }

    /**
     * Синхронное сохранение всего (выключение плагина).
     */
    public void saveAll() {
        saveMeta();
        synchronized (ioLock) {
            for (Flood flood : floods.values()) {
                try {
                    FloodStorage.write(dataFile(flood.name), flood.cells(), flood.floodedBits());
                } catch (IOException ex) {
                    plugin.getLogger().log(Level.WARNING, "Не удалось сохранить данные затопления " + flood.name, ex);
                }
            }
        }
    }
}
