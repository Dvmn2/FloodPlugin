package net.dvmn2.floodplugin.commands;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.math.BlockPosition;
import net.dvmn2.floodplugin.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * /flood create &lt;имя&gt; [мир]
 * /flood delete &lt;имя&gt; [force]
 * /flood list | status [имя]
 * /flood breach add|remove &lt;имя&gt; [x y z]
 * /flood breach list &lt;имя&gt;
 * /flood start|pump|pause|stop|reset &lt;имя&gt;
 * /flood rate &lt;имя&gt; &lt;блоков/с&gt;
 * /flood reload
 * <p>
 * Требует право flood.admin.
 */
public final class FloodCommand {

    private final FloodPlugin plugin;

    public FloodCommand(FloodPlugin plugin) {
        this.plugin = plugin;
    }

    private FloodManager manager() {
        return plugin.getManager();
    }

    public LiteralArgumentBuilder<CommandSourceStack> build() {
        return Commands.literal("flood")
                .requires(src -> src.getSender().hasPermission("flood.admin"))
                .then(Commands.literal("create")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> create(ctx, null))
                                .then(Commands.argument("world", ArgumentTypes.world())
                                        .executes(ctx -> create(ctx, ctx.getArgument("world", World.class))))))
                .then(Commands.literal("delete")
                        .then(nameArg()
                                .executes(ctx -> delete(ctx, false))
                                .then(Commands.literal("force").executes(ctx -> delete(ctx, true)))))
                .then(Commands.literal("list").executes(this::list))
                .then(Commands.literal("status")
                        .executes(this::list)
                        .then(nameArg().executes(this::status)))
                .then(Commands.literal("breach")
                        .then(Commands.literal("add")
                                .then(nameArg()
                                        .executes(ctx -> breachAdd(ctx, false))
                                        .then(posArg().executes(ctx -> breachAdd(ctx, true)))))
                        .then(Commands.literal("remove")
                                .then(nameArg()
                                        .executes(ctx -> breachRemove(ctx, false))
                                        .then(posArg().executes(ctx -> breachRemove(ctx, true)))))
                        .then(Commands.literal("list").then(nameArg().executes(this::breachList))))
                .then(Commands.literal("start").then(nameArg().executes(this::start)))
                .then(Commands.literal("pump").then(nameArg().executes(this::pump)))
                .then(Commands.literal("pause").then(nameArg().executes(this::pause)))
                .then(Commands.literal("stop").then(nameArg().executes(this::pause)))
                .then(Commands.literal("rate")
                        .then(nameArg()
                                .then(Commands.argument("rate", IntegerArgumentType.integer(1))
                                        .executes(this::rate))))
                .then(Commands.literal("reset").then(nameArg().executes(this::reset)))
                .then(Commands.literal("reload").executes(this::reload));
    }

    // ------------------------------------------------------------------
    // Аргументы и утилиты
    // ------------------------------------------------------------------

    private RequiredArgumentBuilder<CommandSourceStack, String> nameArg() {
        return Commands.argument("name", StringArgumentType.word()).suggests(this::suggestNames);
    }

    private RequiredArgumentBuilder<CommandSourceStack, BlockPositionResolver> posArg() {
        return Commands.argument("pos", ArgumentTypes.blockPosition());
    }

    private CompletableFuture<Suggestions> suggestNames(CommandContext<CommandSourceStack> ctx,
                                                        SuggestionsBuilder builder) {
        String typed = builder.getRemainingLowerCase();
        for (String name : manager().names()) {
            if (name.startsWith(typed)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }

    private static void ok(CommandSender sender, Lang.Key key, Object... args) {
        sender.sendMessage(Component.text(Lang.get(key, sender, args), NamedTextColor.GREEN));
    }

    private static void warn(CommandSender sender, Lang.Key key, Object... args) {
        sender.sendMessage(Component.text(Lang.get(key, sender, args), NamedTextColor.YELLOW));
    }

    private static void error(CommandSender sender, Lang.Key key, Object... args) {
        sender.sendMessage(Component.text(Lang.get(key, sender, args), NamedTextColor.RED));
    }

    private static void info(CommandSender sender, String text) {
        sender.sendMessage(Component.text(text, NamedTextColor.GRAY));
    }

    /**
     * Возвращает затопление либо сообщает об ошибке и возвращает null.
     */
    private Flood requireFlood(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        Flood flood = manager().get(name);
        if (flood == null) {
            error(sender, Lang.Key.NO_FLOOD, name);
        }
        return flood;
    }

    /**
     * Мир затопления либо сообщение об ошибке и null.
     */
    private World requireWorld(CommandSender sender, Flood flood) {
        World world = Bukkit.getWorld(flood.worldId);
        if (world == null) {
            error(sender, Lang.Key.WORLD_MISSING, flood.name);
        }
        return world;
    }

    // ------------------------------------------------------------------
    // Команды
    // ------------------------------------------------------------------

    private int create(CommandContext<CommandSourceStack> ctx, World explicitWorld) {
        CommandSender sender = ctx.getSource().getSender();
        String name = StringArgumentType.getString(ctx, "name");
        if (!Flood.isValidName(name)) {
            error(sender, Lang.Key.NAME_INVALID);
            return 0;
        }
        if (manager().get(name) != null) {
            error(sender, Lang.Key.EXISTS, name);
            return 0;
        }
        World world = explicitWorld;
        if (world == null && sender instanceof Player player) {
            world = player.getWorld();
        }
        if (world == null) {
            error(sender, Lang.Key.NEED_WORLD);
            return 0;
        }
        Flood flood = manager().create(name, world);
        ok(sender, Lang.Key.CREATED, name, world.getName(), flood.rate);
        return 1;
    }

    private int delete(CommandContext<CommandSourceStack> ctx, boolean force) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        if (!force && flood.floodedCount() > 0) {
            warn(sender, Lang.Key.DELETE_HAS_WATER, flood.name, flood.floodedCount());
            return 0;
        }
        manager().delete(flood);
        ok(sender, Lang.Key.DELETED, flood.name);
        return 1;
    }

    private int list(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        List<Flood> floods = manager().all();
        if (floods.isEmpty()) {
            info(sender, Lang.get(Lang.Key.LIST_EMPTY, sender));
            return 1;
        }
        info(sender, Lang.get(Lang.Key.LIST_HEADER, sender, floods.size()));
        for (Flood flood : floods) {
            info(sender, Lang.get(Lang.Key.LIST_ENTRY, sender, flood.name, manager().worldName(flood),
                    manager().modeText(flood, sender), flood.floodedCount(), flood.total(), flood.percent(),
                    flood.rate, flood.breaches.size()));
        }
        return 1;
    }

    private int status(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        Integer waterline = flood.waterlineY();
        info(sender, Lang.get(Lang.Key.STATUS, sender, flood.name, manager().worldName(flood),
                manager().modeText(flood, sender), flood.floodedCount(), flood.total(), flood.percent(),
                waterline == null ? "-" : waterline.toString(), flood.rate, flood.breaches.size()));
        return 1;
    }

    /**
     * Координаты пробоины: явные или (для игрока) позиция его ног. null — если взять негде.
     */
    private int[] position(CommandContext<CommandSourceStack> ctx, boolean explicit) throws CommandSyntaxException {
        if (explicit) {
            BlockPosition pos = ctx.getArgument("pos", BlockPositionResolver.class).resolve(ctx.getSource());
            return new int[]{pos.blockX(), pos.blockY(), pos.blockZ()};
        }
        if (ctx.getSource().getSender() instanceof Player player) {
            Location loc = player.getLocation();
            return new int[]{loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()};
        }
        return null;
    }

    private int breachAdd(CommandContext<CommandSourceStack> ctx, boolean explicit) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        int[] p = position(ctx, explicit);
        if (p == null) {
            error(sender, Lang.Key.NEED_POSITION);
            return 0;
        }
        World world = requireWorld(sender, flood);
        if (world == null) {
            return 0;
        }
        if (sender instanceof Player player && !player.getWorld().getUID().equals(flood.worldId)) {
            error(sender, Lang.Key.WRONG_WORLD, world.getName());
            return 0;
        }
        if (!FloodChecks.isSeedValid(world, p[0], p[1], p[2])) {
            error(sender, Lang.Key.BREACH_INVALID);
            return 0;
        }
        if (!manager().addBreach(flood, p[0], p[1], p[2])) {
            warn(sender, Lang.Key.BREACH_EXISTS);
            return 0;
        }
        ok(sender, Lang.Key.BREACH_ADDED, flood.name, p[0], p[1], p[2], flood.breaches.size());
        return 1;
    }

    private int breachRemove(CommandContext<CommandSourceStack> ctx, boolean explicit) throws CommandSyntaxException {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        int[] p = position(ctx, explicit);
        if (p == null) {
            error(sender, Lang.Key.NEED_POSITION);
            return 0;
        }
        if (!manager().removeBreach(flood, p[0], p[1], p[2])) {
            warn(sender, Lang.Key.BREACH_NOT_FOUND, flood.name, p[0], p[1], p[2]);
            return 0;
        }
        ok(sender, Lang.Key.BREACH_REMOVED, flood.name, p[0], p[1], p[2], flood.breaches.size());
        return 1;
    }

    private int breachList(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        if (flood.breaches.isEmpty()) {
            info(sender, Lang.get(Lang.Key.BREACH_LIST_EMPTY, sender));
            return 1;
        }
        info(sender, Lang.get(Lang.Key.BREACH_LIST_HEADER, sender, flood.name, flood.breaches.size()));
        for (long key : flood.breaches) {
            info(sender, Lang.get(Lang.Key.BREACH_LIST_ENTRY, sender, Pos.x(key), Pos.y(key), Pos.z(key)));
        }
        return 1;
    }

    private int start(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        if (manager().isScanning(flood)) {
            warn(sender, Lang.Key.SCANNING_BUSY, flood.name);
            return 0;
        }
        if (flood.breaches.isEmpty()) {
            error(sender, Lang.Key.NO_BREACHES, flood.name, flood.name);
            return 0;
        }
        if (requireWorld(sender, flood) == null) {
            return 0;
        }
        manager().startFlooding(flood);
        if (manager().isScanning(flood)) {
            ok(sender, Lang.Key.SCAN_STARTED, flood.name);
        }
        ok(sender, Lang.Key.START_FLOODING, flood.name, Math.min(flood.rate, manager().maxRate()));
        return 1;
    }

    private int pump(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        if (manager().isScanning(flood)) {
            warn(sender, Lang.Key.SCANNING_BUSY, flood.name);
            return 0;
        }
        if (flood.floodedCount() == 0) {
            warn(sender, Lang.Key.NOTHING_TO_PUMP, flood.name);
            return 0;
        }
        if (requireWorld(sender, flood) == null) {
            return 0;
        }
        manager().startPumping(flood);
        ok(sender, Lang.Key.START_PUMPING, flood.name, Math.min(flood.rate, manager().maxRate()));
        return 1;
    }

    private int pause(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        manager().pause(flood);
        ok(sender, Lang.Key.PAUSED, flood.name, flood.floodedCount(), flood.total());
        return 1;
    }

    private int rate(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        int rate = IntegerArgumentType.getInteger(ctx, "rate");
        if (rate > manager().maxRate()) {
            error(sender, Lang.Key.RATE_TOO_HIGH, manager().maxRate());
            return 0;
        }
        manager().setRate(flood, rate);
        ok(sender, Lang.Key.RATE_SET, flood.name, rate);
        return 1;
    }

    private int reset(CommandContext<CommandSourceStack> ctx) {
        CommandSender sender = ctx.getSource().getSender();
        Flood flood = requireFlood(ctx);
        if (flood == null) {
            return 0;
        }
        if (flood.floodedCount() > 0 && requireWorld(sender, flood) == null) {
            return 0;
        }
        manager().reset(flood);
        if (flood.resetting) {
            ok(sender, Lang.Key.RESET_STARTED, flood.name);
        } else {
            ok(sender, Lang.Key.RESET_DONE, flood.name);
        }
        return 1;
    }

    private int reload(CommandContext<CommandSourceStack> ctx) {
        plugin.reloadAll();
        ok(ctx.getSource().getSender(), Lang.Key.RELOAD_OK);
        return 1;
    }
}
