package me.lovelace.lovechat.commands;

import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Единая административная команда плагина: {@code /lovechatadmin <subcommand>}.
 * <p>
 * Раньше admin-функции (перезагрузка конфигурации) были зарыты внутри {@code /lovechat reload},
 * что не соответствует принятому в экосистеме Love* стилю единой родительской admin-команды
 * с подкомандами. Здесь только reload — остальные admin-возможности Lovechat (глобальная очистка
 * чата, spy) уже имеют собственные явные разрешения и команды и не переносились, чтобы не менять
 * их поведение без необходимости.
 */
public class LoveChatAdminCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("reload", "help");
    private static final List<String> RELOAD_TYPES = List.of("all", "config", "message");

    private final Lovechat plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public LoveChatAdminCommand(@NotNull Lovechat plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("lovechat.admin")) {
            plugin.sendMessage(sender, "no-permission");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> handleReload(sender, args);
            default -> sendHelp(sender);
        }
        return true;
    }

    private void handleReload(@NotNull CommandSender sender, @NotNull String[] args) {
        String type = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";
        switch (type) {
            case "config" -> {
                plugin.reloadConfig();
                plugin.loadDisabledWorlds();
                plugin.registerDynamicChannelCommands();
                plugin.getChatBubbleManager().loadConfig();
                plugin.sendMessage(sender, "reload-success");
            }
            case "message", "messages" -> {
                plugin.loadMessages();
                plugin.sendMessage(sender, "reload-messages-success");
            }
            default -> {
                plugin.reloadConfig();
                plugin.loadMessages();
                plugin.loadDisabledWorlds();
                plugin.registerDynamicChannelCommands();
                plugin.getChatBubbleManager().loadConfig();
                plugin.sendMessage(sender, "reload-success");
            }
        }
    }

    private void sendHelp(@NotNull CommandSender sender) {
        sender.sendMessage(mm.deserialize(plugin.getRawMsg("admin-help-header")));
        sender.sendMessage(mm.deserialize(plugin.getRawMsg("admin-help-reload")));
        sender.sendMessage(mm.deserialize(plugin.getRawMsg("admin-help-footer")));
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!sender.hasPermission("lovechat.admin")) return Collections.emptyList();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], SUBCOMMANDS, new ArrayList<>());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("reload")) {
            return StringUtil.copyPartialMatches(args[1], RELOAD_TYPES, new ArrayList<>());
        }
        return Collections.emptyList();
    }
}
