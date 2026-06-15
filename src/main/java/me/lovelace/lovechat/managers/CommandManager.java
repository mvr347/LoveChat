package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@SuppressWarnings("RedundantReturnStatement")
public class CommandManager implements CommandExecutor, TabCompleter {
    private final Lovechat plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public CommandManager(@NotNull Lovechat plugin) {
        this.plugin = plugin;
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        processCommand(sender, command, args);
        return true;
    }

    @SuppressWarnings({"IfStatementWithTooManyBranches", "IfCanBeSwitch"})
    private void processCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String[] args) {
        if (sender instanceof Player p && plugin.isWorldDisabled(p.getWorld().getName())) {
            plugin.sendMessage(p, "world-disabled");
            return;
        }
        boolean isAchCmd = command.getName().equalsIgnoreCase("lovechat") || command.getName().equalsIgnoreCase("chat");
        boolean isSilentCmd = command.getName().equalsIgnoreCase("silent") || (isAchCmd && args.length >= 1 && args[0].equalsIgnoreCase("silent"));
        boolean isTagToggleCmd = command.getName().equalsIgnoreCase("tagtoggle") || (isAchCmd && args.length >= 1 && args[0].equalsIgnoreCase("tagtoggle"));
        boolean isSpyCmd = command.getName().equalsIgnoreCase("spy") || (isAchCmd && args.length >= 1 && args[0].equalsIgnoreCase("spy"));
        boolean isIgnoreCmd = command.getName().equalsIgnoreCase("ignorechat") || (isAchCmd && args.length >= 1 && (args[0].equalsIgnoreCase("ignore") || args[0].equalsIgnoreCase("ignorechat")));
        boolean isChatClearCmd = command.getName().equalsIgnoreCase("chatclear") || command.getName().equalsIgnoreCase("cc") || (isAchCmd && args.length >= 1 && (args[0].equalsIgnoreCase("chatclear") || args[0].equalsIgnoreCase("cc")));

        // --- CHAT CLEAR ---
        if (isChatClearCmd) {
            int argStartIndex = (command.getName().equalsIgnoreCase("lovechat") || command.getName().equalsIgnoreCase("chat")) ? 1 : 0;
            String mode = "personal";
            boolean clearConsole = false;

            for (int i = argStartIndex; i < args.length; i++) {
                String arg = args[i].toLowerCase();
                if (arg.equals("all")) mode = "all";
                else if (arg.equals("users")) mode = "users";
                else if (arg.equals("-c")) clearConsole = true;
            }

            if (!mode.equals("personal") && !sender.hasPermission("lovechat.clear.global")) {
                plugin.sendMessage(sender, "no-permission");
                return;
            }

            if (clearConsole && !sender.hasPermission("lovechat.clear.console")) {
                plugin.sendMessage(sender, "no-permission");
                return;
            }

            if (mode.equals("personal")) {
                if (!(sender instanceof Player p)) {
                    plugin.sendMessage(sender, "player-only");
                    return;
                }
                plugin.clearChatForPlayer(p, true);
                plugin.sendMessage(p, "chatclear-personal");
            } else {
                boolean keepStaff = mode.equals("users");
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.clearChatForPlayer(p, keepStaff);
                }
                if (clearConsole) {
                    Bukkit.getConsoleSender().sendMessage(plugin.getClearChatComponent());
                }
                for (Player p : Bukkit.getOnlinePlayers()) {
                    plugin.sendMessage(p, keepStaff ? "chatclear-global-users" : "chatclear-global-all");
                }
            }
            return;
        }

        // --- SPY MODE ---
        if (isSpyCmd) {
            if (!sender.hasPermission("lovechat.spy")) {
                plugin.sendMessage(sender, "no-permission");
                return;
            }
            if (sender instanceof Player p) {
                plugin.toggleSpy(p.getUniqueId());
                if (plugin.isSpy(p.getUniqueId())) plugin.sendMessage(p, "spy-enabled");
                else plugin.sendMessage(p, "spy-disabled");
            }
            return;
        }

        // --- SILENT MODE ---
        if (isSilentCmd) {
            if (!sender.hasPermission("lovechat.silent")) {
                plugin.sendMessage(sender, "no-permission");
                return;
            }
            if (sender instanceof Player p) {
                plugin.toggleSilent(p.getUniqueId());
                if (plugin.isSilent(p.getUniqueId())) plugin.sendMessage(p, "silent-enabled");
                else plugin.sendMessage(p, "silent-disabled");
            }
            return;
        }

        // --- TAG TOGGLE ---
        if (isTagToggleCmd) {
            if (!sender.hasPermission("lovechat.tagtoggle")) {
                plugin.sendMessage(sender, "no-permission");
                return;
            }

            Player target = null;
            if (command.getName().equalsIgnoreCase("tagtoggle")) {
                if (args.length >= 1 && sender.hasPermission("lovechat.admin")) target = Bukkit.getPlayer(args[0]);
                else if (sender instanceof Player) target = (Player) sender;
            } else {
                if (args.length >= 2 && sender.hasPermission("lovechat.admin")) target = Bukkit.getPlayer(args[1]);
                else if (sender instanceof Player) target = (Player) sender;
            }

            if (target == null) {
                plugin.sendMessage(sender, "player-not-found");
                return;
            }

            plugin.toggleTagsDisabled(target.getUniqueId());
            boolean disabled = plugin.hasTagsDisabled(target.getUniqueId());

            if (sender.equals(target)) {
                plugin.sendMessage(sender, disabled ? "tags-disabled-self" : "tags-enabled-self");
            } else {
                plugin.sendMessage(sender, disabled ? "tags-disabled-other" : "tags-enabled-other", "{player}", target.getName());
            }
            return;
        }

        // --- CHANNEL SWITCHER ---
        if (command.getName().equalsIgnoreCase("channel") && sender instanceof Player p) {
            ConfigurationSection channels = plugin.getConfig().getConfigurationSection("colors.channels");
            if (channels == null) return;

            List<String> availableChannels = new ArrayList<>();
            for (String key : channels.getKeys(false)) {
                String perm = channels.getString(key + ".permission", "NONE");
                if (perm.equalsIgnoreCase("NONE") || p.hasPermission(perm)) {
                    availableChannels.add(key);
                }
            }

            if (availableChannels.isEmpty()) {
                plugin.sendMessage(p, "no-permission-channel");
                return;
            }

            if (args.length == 0) {
                String current = plugin.getDefaultChannel(p.getUniqueId());
                int currentIndex = availableChannels.indexOf(current);
                String nextChannel = availableChannels.get((currentIndex + 1) % availableChannels.size());

                plugin.setDefaultChannel(p.getUniqueId(), nextChannel);
                plugin.getDatabaseManager().saveDefaultChannel(p.getUniqueId(), nextChannel);
                plugin.sendMessage(p, "channel-switched", "{channel}", nextChannel);
            } else {
                String requested = args[0].toLowerCase();
                if (availableChannels.contains(requested)) {
                    plugin.setDefaultChannel(p.getUniqueId(), requested);
                    plugin.getDatabaseManager().saveDefaultChannel(p.getUniqueId(), requested);
                    plugin.sendMessage(p, "channel-switched", "{channel}", requested);
                } else {
                    plugin.sendMessage(p, "channel-not-found");
                }
            }
            return;
        }

        // --- IGNORE CHAT ---
        if (isIgnoreCmd && sender instanceof Player p) {
            String targetName = null;
            if (command.getName().equalsIgnoreCase("ignorechat") && args.length >= 1) {
                targetName = args[0];
            } else if (isAchCmd && args.length >= 2) {
                targetName = args[1];
            }

            if (targetName == null) {
                plugin.sendMessage(p, "ignore-usage");
                return;
            }

            Player target = Bukkit.getPlayer(targetName);
            if (target == null) {
                plugin.sendMessage(p, "ignore-not-found");
                return;
            }
            if (target.equals(p)) {
                plugin.sendMessage(p, "ignore-self");
                return;
            }

            if (target.hasPermission("lovechat.admin") && plugin.getConfig().getBoolean("ignore.admins-bypass", true)) {
                plugin.sendMessage(p, "ignore-admin");
                return;
            }

            plugin.toggleIgnore(p.getUniqueId(), target.getUniqueId());
            if (plugin.isIgnoring(p.getUniqueId(), target.getUniqueId())) {
                plugin.sendMessage(p, "ignore-added", "{player}", target.getName());
            } else {
                plugin.sendMessage(p, "ignore-removed", "{player}", target.getName());
            }
            return;
        }

        // --- ИСПРАВЛЕНО: MESSAGE DELETE ---
        if (command.getName().equalsIgnoreCase("messagedelete") || command.getName().equalsIgnoreCase("md")) {
            if (args.length == 0) return;
            try {
                int msgId = Integer.parseInt(args[0]);
                Lovechat.MessageData data = plugin.getMessageDataCache().getIfPresent(msgId);

                // Перевел права на стандартную модель, 
                boolean isAdmin = sender.hasPermission("lovechat.delete.admin") || sender.isOp();

                if (data == null) {
                    if (isAdmin) plugin.deleteMessageVisual(msgId, sender);
                    else plugin.sendMessage(sender, "delete-not-found");
                    return;
                }

                boolean isOwn = sender instanceof Player player && data.owner().equals(player.getUniqueId());

                if (isAdmin || (isOwn && sender.hasPermission("lovechat.delete"))) {
                    plugin.deleteMessageVisual(msgId, sender);
                } else {
                    plugin.sendMessage(sender, "delete-not-yours");
                }
            } catch (NumberFormatException ignored) {}
            return;
        }

        // --- ИСПРАВЛЕНО: MESSAGE EDIT ---
        if (command.getName().equalsIgnoreCase("messageedit") || command.getName().equalsIgnoreCase("medit")) {
            if (!(sender instanceof Player p)) return;

            if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
                if (plugin.getEditSession(p.getUniqueId()) != null) {
                    plugin.removeEditSession(p.getUniqueId());
                    p.sendMessage(mm.deserialize("<red>Редактирование отменено.</red>"));
                }
                return;
            }

            if (args.length == 0) return;

            try {
                int msgId = Integer.parseInt(args[0]);
                Lovechat.MessageData data = plugin.getMessageDataCache().getIfPresent(msgId);

                if (data == null) {
                    plugin.sendMessage(sender, "edit-not-found");
                    return;
                }

                boolean isAdmin = p.hasPermission("lovechat.edit.admin") || p.isOp();
                boolean isOwn = data.owner().equals(p.getUniqueId());

                if (!isAdmin && !(isOwn && p.hasPermission("lovechat.edit"))) {
                    plugin.sendMessage(sender, "delete-not-yours"); // Здесь в messages.yml должно быть "edit-not-yours" по-хорошему
                    return;
                }

                plugin.startEditSession(p, msgId, data.rawText());

            } catch (NumberFormatException ignored) {}
            return;
        }

        // --- ACH COMMAND ---
        if (isAchCmd) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("lovechat.admin")) {
                String type = (args.length > 1) ? args[1].toLowerCase(Locale.ROOT) : "all";
                if (type.equals("config")) {
                    plugin.reloadConfig();
                    plugin.registerDynamicChannelCommands();
                    plugin.getChatBubbleManager().loadConfig();
                    plugin.sendMessage(sender, "reload-success");
                } else if (type.equals("message") || type.equals("messages")) {
                    plugin.loadMessages();
                    plugin.sendMessage(sender, "reload-messages-success");
                } else {
                    plugin.reloadConfig();
                    plugin.loadMessages();
                    plugin.registerDynamicChannelCommands();
                    plugin.getChatBubbleManager().loadConfig();
                    plugin.sendMessage(sender, "reload-success");
                }
                return;
            }

            if (args.length == 0) {
                sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-header")));
                if (sender.hasPermission("lovechat.admin")) sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-reload")));
                sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-chat")));
                sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-silent")));
                sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-ignore")));
                sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-chatclear")));
                if (sender.hasPermission("lovechat.spy")) sender.sendMessage(mm.deserialize(plugin.getRawMsg("help-spy")));
                return;
            }
        }
    }

    @Nullable
    @Override
    @SuppressWarnings("NullableProblems")
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        List<String> completions = new ArrayList<>();

        if (command.getName().equalsIgnoreCase("lovechat")) {
            if (args.length == 1) {
                if (sender.hasPermission("lovechat.admin")) completions.add("reload");
                completions.add("silent");
                completions.add("ignore");
                completions.add("chatclear");
                if (sender.hasPermission("lovechat.tagtoggle")) completions.add("tagtoggle");
                if (sender.hasPermission("lovechat.spy")) completions.add("spy");
                return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("lovechat.admin")) {
                completions.add("all");
                completions.add("config");
                completions.add("message");
                completions.add("messages");
                return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
            }
            else if (args.length == 2 && args[0].equalsIgnoreCase("chatclear")) {
                if (sender.hasPermission("lovechat.clear.global")) {
                    completions.add("all");
                    completions.add("users");
                }
                return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
            }
            else if (args.length == 3 && args[0].equalsIgnoreCase("chatclear")) {
                if (sender.hasPermission("lovechat.clear.console")) completions.add("-c");
                return StringUtil.copyPartialMatches(args[2], completions, new ArrayList<>());
            }
            else if (args.length == 2 && (args[0].equalsIgnoreCase("tagtoggle") || args[0].equalsIgnoreCase("ignore"))) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
                return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
            }
        }

        if (command.getName().equalsIgnoreCase("chatclear") || command.getName().equalsIgnoreCase("cc")) {
            if (args.length == 1 && sender.hasPermission("lovechat.clear.global")) {
                completions.add("all");
                completions.add("users");
                return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
            } else if (args.length == 2 && sender.hasPermission("lovechat.clear.console")) {
                completions.add("-c");
                return StringUtil.copyPartialMatches(args[1], completions, new ArrayList<>());
            }
        }

        if (command.getName().equalsIgnoreCase("channel")) {
            if (args.length == 1) {
                ConfigurationSection channels = plugin.getConfig().getConfigurationSection("colors.channels");
                if (channels != null) {
                    for (String key : channels.getKeys(false)) {
                        String perm = channels.getString(key + ".permission", "NONE");
                        if (perm.equalsIgnoreCase("NONE") || sender.hasPermission(perm)) {
                            completions.add(key);
                        }
                    }
                }
                return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
            }
        }

        if (command.getName().equalsIgnoreCase("tagtoggle") && sender.hasPermission("lovechat.admin")) {
            if (args.length == 1) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
                return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
            }
        }

        if (command.getName().equalsIgnoreCase("ignorechat")) {
            if (args.length == 1) {
                for (Player p : Bukkit.getOnlinePlayers()) completions.add(p.getName());
                return StringUtil.copyPartialMatches(args[0], completions, new ArrayList<>());
            }
        }

        return Collections.emptyList();
    }
}
