package net.dvmn2.floodplugin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

public final class Lang {

    public enum Key {
        NO_FLOOD,
        NAME_INVALID,
        EXISTS,
        NEED_WORLD,
        CREATED,
        DELETED,
        DELETE_HAS_WATER,
        LIST_HEADER,
        LIST_EMPTY,
        LIST_ENTRY,
        STATUS,
        BREACH_ADDED,
        BREACH_EXISTS,
        BREACH_REMOVED,
        BREACH_NOT_FOUND,
        BREACH_INVALID,
        BREACH_LIST_HEADER,
        BREACH_LIST_ENTRY,
        BREACH_LIST_EMPTY,
        NEED_POSITION,
        WRONG_WORLD,
        NO_BREACHES,
        SCANNING_BUSY,
        SCAN_STARTED,
        SCAN_DONE,
        SCAN_FAIL_LIMIT,
        SCAN_FAIL_NO_SEED,
        WORLD_MISSING,
        START_FLOODING,
        START_PUMPING,
        NOTHING_TO_PUMP,
        PAUSED,
        RATE_SET,
        RATE_TOO_HIGH,
        RESET_STARTED,
        RESET_DONE,
        FLOODED_FULL,
        PUMPED_FULL,
        RELOAD_OK,
        MODE_IDLE,
        MODE_FLOODING,
        MODE_PUMPING,
        MODE_SCANNING
    }

    private static final Map<Key, String> RU = new EnumMap<>(Key.class);
    private static final Map<Key, String> EN = new EnumMap<>(Key.class);

    private static void put(Key key, String ru, String en) {
        RU.put(key, ru);
        EN.put(key, en);
    }

    static {
        put(Key.NO_FLOOD, "Затопление '%s' не найдено.", "Flood '%s' was not found.");
        put(Key.NAME_INVALID, "Имя: только a-z, 0-9, '_' и '-', до 32 символов.",
                "Name: only a-z, 0-9, '_' and '-', up to 32 characters.");
        put(Key.EXISTS, "Затопление '%s' уже существует.", "Flood '%s' already exists.");
        put(Key.NEED_WORLD, "Из консоли укажите мир: /flood create <имя> <мир>.",
                "From the console specify the world: /flood create <name> <world>.");
        put(Key.CREATED, "Затопление '%s' создано (мир: %s, скорость: %d блоков/с). Добавьте пробоину: /flood breach add <имя>",
                "Flood '%s' created (world: %s, rate: %d blocks/s). Add a breach: /flood breach add <name>");
        put(Key.DELETED, "Затопление '%s' удалено.", "Flood '%s' deleted.");
        put(Key.DELETE_HAS_WATER,
                "В '%1$s' осталось %2$d залитых блоков. Откачайте их (/flood reset %1$s) или удалите принудительно: /flood delete %1$s force",
                "'%1$s' still has %2$d flooded blocks. Drain them (/flood reset %1$s) or delete forcibly: /flood delete %1$s force");
        put(Key.LIST_HEADER, "Затопления (%d):", "Floods (%d):");
        put(Key.LIST_EMPTY, "Затоплений нет. Создайте: /flood create <имя>", "No floods yet. Create one: /flood create <name>");
        put(Key.LIST_ENTRY, " - %s | %s | %s | %d/%d (%d%%) | %d бл/с | пробоин: %d",
                " - %s | %s | %s | %d/%d (%d%%) | %d bl/s | breaches: %d");
        put(Key.STATUS,
                "Затопление '%s'\nМир: %s\nСостояние: %s\nЗалито: %d из %d блоков (%d%%)\nУровень воды: Y=%s\nСкорость: %d блоков/с\nПробоин: %d",
                "Flood '%s'\nWorld: %s\nState: %s\nFlooded: %d of %d blocks (%d%%)\nWater level: Y=%s\nRate: %d blocks/s\nBreaches: %d");
        put(Key.BREACH_ADDED, "Пробоина добавлена в '%s': %d, %d, %d (всего: %d).",
                "Breach added to '%s': %d, %d, %d (total: %d).");
        put(Key.BREACH_EXISTS, "Такая пробоина уже есть.", "This breach already exists.");
        put(Key.BREACH_REMOVED, "Пробоина удалена из '%s': %d, %d, %d (осталось: %d).",
                "Breach removed from '%s': %d, %d, %d (left: %d).");
        put(Key.BREACH_NOT_FOUND, "В '%s' нет пробоины в точке %d, %d, %d.", "'%s' has no breach at %d, %d, %d.");
        put(Key.BREACH_INVALID, "В этой точке и рядом нет воздуха или заливаемого блока.",
                "There is no air or floodable block at or next to this point.");
        put(Key.BREACH_LIST_HEADER, "Пробоины '%s' (%d):", "Breaches of '%s' (%d):");
        put(Key.BREACH_LIST_ENTRY, " - %d, %d, %d", " - %d, %d, %d");
        put(Key.BREACH_LIST_EMPTY, "Пробоин нет.", "No breaches.");
        put(Key.NEED_POSITION, "Из консоли укажите координаты.", "From the console specify coordinates.");
        put(Key.WRONG_WORLD, "Пробоина должна быть в мире '%s'.", "The breach must be in world '%s'.");
        put(Key.NO_BREACHES, "У '%s' нет пробоин. Добавьте: /flood breach add %s",
                "'%s' has no breaches. Add one: /flood breach add %s");
        put(Key.SCANNING_BUSY, "'%s': идёт сканирование отсека, подождите.", "'%s': the compartment is being scanned, please wait.");
        put(Key.SCAN_STARTED, "'%s': сканирую отсек от пробоин...", "'%s': scanning the compartment from the breaches...");
        put(Key.SCAN_DONE, "'%s': отсек просканирован, блоков для заливки: %d.", "'%s': scan finished, blocks to flood: %d.");
        put(Key.SCAN_FAIL_LIMIT,
                "'%s': отсек больше лимита (%d блоков) — вероятно, корпус не герметичен. Проверьте обшивку или увеличьте settings.max-region-blocks.",
                "'%s': the compartment exceeds the limit (%d blocks) — the hull is probably not watertight. Check the hull or raise settings.max-region-blocks.");
        put(Key.SCAN_FAIL_NO_SEED, "'%s': в точках пробоин нет воздуха или заливаемых блоков (или чанк не загружен).",
                "'%s': there is no air or floodable block at the breaches (or the chunk is not loaded).");
        put(Key.WORLD_MISSING, "Мир затопления '%s' не загружен.", "The world of flood '%s' is not loaded.");
        put(Key.START_FLOODING, "Затопление '%s' запущено (%d блоков/с).", "Flooding '%s' started (%d blocks/s).");
        put(Key.START_PUMPING, "Откачка '%s' запущена (%d блоков/с).", "Pumping '%s' started (%d blocks/s).");
        put(Key.NOTHING_TO_PUMP, "В '%s' нет залитых блоков.", "'%s' has no flooded blocks.");
        put(Key.PAUSED, "'%s' приостановлено. Залито %d из %d блоков.", "'%s' paused. Flooded %d of %d blocks.");
        put(Key.RATE_SET, "'%s': скорость = %d блоков/с.", "'%s': rate = %d blocks/s.");
        put(Key.RATE_TOO_HIGH, "Максимальная скорость: %d блоков/с (settings.max-rate).", "Maximum rate: %d blocks/s (settings.max-rate).");
        put(Key.RESET_STARTED, "'%s': запущена полная откачка, по окончании план будет очищен.",
                "'%s': full drain started, the plan will be cleared when done.");
        put(Key.RESET_DONE, "'%s': вода убрана, затопление сброшено.", "'%s': water removed, flood reset.");
        put(Key.FLOODED_FULL, "'%s': отсек полностью затоплен (%d блоков).", "'%s': the compartment is fully flooded (%d blocks).");
        put(Key.PUMPED_FULL, "'%s': вода откачана.", "'%s': water pumped out.");
        put(Key.RELOAD_OK, "Конфиг перезагружен.", "Config reloaded.");
        put(Key.MODE_IDLE, "ожидание", "idle");
        put(Key.MODE_FLOODING, "затопление", "flooding");
        put(Key.MODE_PUMPING, "откачка", "pumping");
        put(Key.MODE_SCANNING, "сканирование", "scanning");
    }

    private static volatile String configuredLanguage = "auto";

    private Lang() {
    }

    public static void setLanguage(String language) {
        if (language == null || language.isBlank()) {
            configuredLanguage = "auto";
            return;
        }
        configuredLanguage = language.toLowerCase(Locale.ROOT);
    }

    public static String get(Key key, CommandSender sender, Object... args) {
        Map<Key, String> table = resolveTable(sender);
        String pattern = table.getOrDefault(key, RU.get(key));
        return args.length == 0 ? pattern : String.format(Locale.ROOT, pattern, args);
    }

    private static Map<Key, String> resolveTable(CommandSender sender) {
        return switch (configuredLanguage) {
            case "en" -> EN;
            case "ru" -> RU;
            default -> autoResolve(sender);
        };
    }

    private static Map<Key, String> autoResolve(CommandSender sender) {
        if (sender instanceof Player player) {
            String langCode = player.locale().getLanguage();
            return "ru".equalsIgnoreCase(langCode) ? RU : EN;
        }
        return EN;
    }
}
