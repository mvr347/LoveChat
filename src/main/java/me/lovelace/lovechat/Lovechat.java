package me.lovelace.lovechat;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import me.clip.placeholderapi.PlaceholderAPI;
import me.lovelace.lovechat.depends.CMISkinUtil;
import me.lovelace.lovechat.depends.ProtocolLibHook;
import me.lovelace.lovechat.expansion.ChatPlaceholderExpansion;
import me.lovelace.lovechat.listeners.ChatListener;
import me.lovelace.lovechat.managers.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.key.Key;
import me.lovelace.lovechat.depends.HeadComponentUtil;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandExecutor;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Основной класс плагина Lovechat.
 * Строго оптимизирован под Paper API 1.21.11 (совместим с Folia) и Java 21.
 */
@SuppressWarnings({"DuplicatedCode", "RedundantReturnStatement"})
public final class Lovechat extends JavaPlugin {

    private static Lovechat instance;
    private DatabaseManager databaseManager;
    private ChatBubbleManager chatBubbleManager;
    private ChatPlaceholderExpansion placeholderExpansion;

    private YamlConfiguration messagesConfig;

    public record ChatLine(int messageId, Component component, boolean isPluginMessage) {}
    public record MessageData(UUID owner, String channel, String rawText, boolean isStaff) {
    }

    public record EditSession(int messageId, String oldText) {}

    private final Map<UUID, EditSession> activeEditSessions = new ConcurrentHashMap<>();

    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private final Map<String, Integer> customChannels = new ConcurrentHashMap<>();
    private final Map<UUID, String> defaultChannels = new ConcurrentHashMap<>();
    private final Set<UUID> silentPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> ignoredPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> tagsDisabledPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spyPlayers = ConcurrentHashMap.newKeySet();

    private final Map<UUID, Integer> lastMessageIds = new ConcurrentHashMap<>();

    private final Cache<UUID, List<ChatLine>> chatHistory = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(2000)
            .build();
    private final Cache<Integer, MessageData> messageDataCache = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .build();

    private final Cache<UUID, List<String>> ignoredSentStrings = CacheBuilder.newBuilder()
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .build();
    private final Cache<UUID, Boolean> redrawIgnoreCache = CacheBuilder.newBuilder()
            .expireAfterWrite(500, TimeUnit.MILLISECONDS)
            .build();

    private final Component clearChatComponent = buildClearChat();

    private Component buildClearChat() {
        Component comp = Component.empty();
        for (int i = 0; i < 100; i++) {
            comp = comp.append(Component.newline());
        }
        return comp;
    }

    public static @NotNull Lovechat getInstance() {
        if (instance == null) {
            throw new IllegalStateException("Plugin instance not initialized");
        }
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();
        loadMessages();

        databaseManager = new DatabaseManager(this);
        databaseManager.init();
        databaseManager.clearAllMessagesSync();

        CMISkinUtil.init();

        chatBubbleManager = new ChatBubbleManager(this);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        new ProtocolLibHook(this).register();
        registerPlaceholderExpansion();

        CommandManager cmdManager = new CommandManager(this);
        registerSafeCommand("lovechat", cmdManager);
        registerSafeCommand("channel", cmdManager);
        registerSafeCommand("ignorechat", cmdManager);
        registerSafeCommand("messagedelete", cmdManager);
        registerSafeCommand("messageedit", cmdManager);
        registerSafeCommand("silent", cmdManager);
        registerSafeCommand("tagtoggle", cmdManager);
        registerSafeCommand("spy", cmdManager);
        registerSafeCommand("chatclear", cmdManager);

        registerDynamicChannelCommands();

        long runEveryTicks = 20L * 60L * 10L;
        long deleteOlderThanMillis = TimeUnit.HOURS.toMillis(1);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> {
                    databaseManager.cleanOldMessages(deleteOlderThanMillis);
                    messageDataCache.cleanUp();
                    chatHistory.cleanUp();
                },
                200L,
                runEveryTicks
        );
    }

    @Override
    public void onDisable() {
        if (chatBubbleManager != null) chatBubbleManager.clearAll();

        HandlerList.unregisterAll(this);

        if (databaseManager != null) databaseManager.close();
        if (placeholderExpansion != null) {
            placeholderExpansion.unregister();
            placeholderExpansion = null;
        }

        instance = null;
    }

    public void loadMessages() {
        File messagesFile = new File(getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) saveResource("messages.yml", false);
        messagesConfig = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public @NotNull String getRawMsg(@NotNull String key) {
        if (messagesConfig == null) loadMessages();
        String msg = messagesConfig.getString(key);
        return msg == null ? "<red>Message not found: " + key + "</red>" : msg;
    }

    public void sendMessage(@NotNull org.bukkit.command.CommandSender sender, @NotNull String key, @NotNull String... placeholders) {
        Component component = MiniMessage.miniMessage().deserialize(getRawMsg(key));

        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                final String target = placeholders[i].replace("{", "").replace("}", "");
                final String replacement = placeholders[i + 1];
                component = component.replaceText(builder -> builder.matchLiteral(target).replacement(replacement));
            }
        }
        sender.sendMessage(component);
    }

    private void registerSafeCommand(@NotNull String name, @NotNull CommandExecutor executor) {
        var command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            if (executor instanceof org.bukkit.command.TabCompleter tabCompleter) {
                command.setTabCompleter(tabCompleter);
            }
        }
    }

    public void registerDynamicChannelCommands() {
        getServer().getGlobalRegionScheduler().execute(this, () -> {
            var channels = getConfig().getConfigurationSection("colors.channels");
            if (channels == null) return;
            // Логика регистрации команд канала
        });
    }

    private void registerPlaceholderExpansion() {
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            placeholderExpansion = new ChatPlaceholderExpansion(this);
            placeholderExpansion.register();
        }
    }

    public int getNextMessageId() { return messageIdCounter.getAndIncrement(); }
    public void setLastMessageId(@NotNull UUID uuid, int messageId) { lastMessageIds.put(uuid, messageId); }
    public @Nullable Integer getLastMessageId(@NotNull UUID uuid) { return lastMessageIds.get(uuid); }
    public @NotNull Cache<Integer, MessageData> getMessageDataCache() { return messageDataCache; }

    public void addChatLineAndSend(@NotNull Player player, int messageId, @NotNull Component component) {
        if (isWorldDisabled(player.getWorld().getName())) return;
        try {
            List<ChatLine> history = chatHistory.get(player.getUniqueId(), () -> Collections.synchronizedList(new LinkedList<>()));
            synchronized (history) {
                trimHistory(history);
                history.add(new ChatLine(messageId, component, false));
            }
        } catch (ExecutionException ignored) {}

        player.sendMessage(component);
    }

    public @NotNull Component getClearChatComponent() { return clearChatComponent; }
    public void clearChatForPlayer(@NotNull Player player, @SuppressWarnings("unused") boolean keepStaff) {
        if (isWorldDisabled(player.getWorld().getName())) return;
        player.sendMessage(clearChatComponent);
    }
    public @NotNull Map<String, Integer> getCustomChannels() { return customChannels; }
    public @NotNull String getDefaultChannel(@NotNull UUID uuid) { return defaultChannels.getOrDefault(uuid, "local"); }
    public void setDefaultChannel(@NotNull UUID uuid, @NotNull String channel) { defaultChannels.put(uuid, channel); }
    public void removeDefaultChannel(@NotNull UUID uuid) { defaultChannels.remove(uuid); }
    public @NotNull Set<UUID> getSilentPlayers() { return silentPlayers; }
    public boolean isSilent(@NotNull UUID uuid) { return silentPlayers.contains(uuid); }
    public void toggleSilent(@NotNull UUID uuid) { if (!silentPlayers.remove(uuid)) silentPlayers.add(uuid); }
    @SuppressWarnings("unused")
    public @NotNull Set<UUID> getSpyPlayers() { return spyPlayers; }
    public boolean isSpy(@NotNull UUID uuid) { return spyPlayers.contains(uuid); }
    public void toggleSpy(@NotNull UUID uuid) { if (!spyPlayers.remove(uuid)) spyPlayers.add(uuid); }
    public boolean hasTagsDisabled(@NotNull UUID uuid) { return tagsDisabledPlayers.contains(uuid); }
    public void setTagsDisabled(@NotNull UUID uuid, boolean disabled) { if (disabled) tagsDisabledPlayers.add(uuid); else tagsDisabledPlayers.remove(uuid); }
    public void toggleTagsDisabled(@NotNull UUID uuid) { if (!tagsDisabledPlayers.remove(uuid)) tagsDisabledPlayers.add(uuid); }
    public boolean isIgnoring(@NotNull UUID ignorer, @NotNull UUID ignored) { Set<UUID> ignoredSet = ignoredPlayers.get(ignorer); return ignoredSet != null && ignoredSet.contains(ignored); }
    public void toggleIgnore(@NotNull UUID ignorer, @NotNull UUID ignored) { Set<UUID> ignoredSet = ignoredPlayers.computeIfAbsent(ignorer, k -> ConcurrentHashMap.newKeySet()); if (!ignoredSet.remove(ignored)) ignoredSet.add(ignored); }
    public void loadIgnores(@NotNull UUID uuid, @NotNull Set<UUID> ignores) { ignoredPlayers.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).addAll(ignores); }
    public boolean isWorldDisabled(@NotNull String worldName) {
        List<String> disabled = getConfig().getStringList("general.disabled-worlds");
        if (disabled.isEmpty()) return false;
        for (String w : disabled) {
            if (w != null && w.equalsIgnoreCase(worldName)) return true;
        }
        return false;
    }
    public @NotNull DatabaseManager getDatabaseManager() { return databaseManager; }
    public @NotNull ChatBubbleManager getChatBubbleManager() { return chatBubbleManager; }

    public boolean shouldIgnorePacket(UUID uuid, String plainText) {
        Boolean ignore = redrawIgnoreCache.getIfPresent(uuid);
        if (ignore != null && ignore) return true;
        List<String> ignoredStrings = ignoredSentStrings.getIfPresent(uuid);
        return ignoredStrings != null && ignoredStrings.contains(plainText);
    }

    public void recordSystemMessageFromPacket(Player player, Component component) {
        if (isWorldDisabled(player.getWorld().getName())) return;
        int messageId = getNextMessageId();
        try {
            List<ChatLine> history = chatHistory.get(player.getUniqueId(), () -> Collections.synchronizedList(new LinkedList<>()));
            // Обязательная синхронизация при пакетном изменении коллекции!
            synchronized (history) {
                trimHistory(history);
                history.add(new ChatLine(messageId, component, true));
            }
        } catch (ExecutionException ignored) {}
    }

    private static void trimHistory(@NotNull List<ChatLine> history) {
        while (history.size() >= 100) {
            if (history instanceof Deque<?> deque) {
                deque.removeFirst();
            } else {
                history.remove(0);
            }
        }
    }

    public void deleteMessageVisual(int messageId, @NotNull org.bukkit.command.CommandSender sender) {
        me.lovelace.lovechat.api.LovechatAPI.LovechatDeleteEvent deleteEvent =
                new me.lovelace.lovechat.api.LovechatAPI.LovechatDeleteEvent(messageId, sender);
        Bukkit.getPluginManager().callEvent(deleteEvent);

        databaseManager.deleteMessage(messageId);
        getMessageDataCache().invalidate(messageId);

        boolean fullDelete = getConfig().getBoolean("messagedelete.full-delete", false);
        Component replacement = MiniMessage.miniMessage().deserialize(
                getConfig().getString("messagedelete.replacement-text", "<gray><i>Сообщение было удалено</i></gray>")
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isWorldDisabled(p.getWorld().getName())) {
                continue;
            }
            List<ChatLine> history = chatHistory.getIfPresent(p.getUniqueId());
            if (history == null) continue;

            boolean found = false;
            List<ChatLine> toSend = new ArrayList<>();

            synchronized (history) {
                for (int i = 0; i < history.size(); i++) {
                    ChatLine cl = history.get(i);
                    if (cl.messageId() == messageId && !cl.isPluginMessage()) {
                        found = true;
                        if (fullDelete) {
                            history.remove(i);
                        } else {
                            history.set(i, new ChatLine(messageId, replacement, false));
                        }
                        break;
                    }
                }
                if (found) {
                    toSend.addAll(history);
                }
            }

            if (found) {
                redrawIgnoreCache.put(p.getUniqueId(), true);

                // ИСПРАВЛЕНИЕ ЛАГА: Очистка и отправка истории идут строго в одном тике
                p.getScheduler().run(this, scheduledTask -> {
                    p.sendMessage(clearChatComponent);
                    for (ChatLine line : toSend) {
                        p.sendMessage(line.component());
                    }
                }, null);
            }
        }
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>Сообщение #" + messageId + " удалено.</green>"));
    }

    public void editMessageVisual(int messageId, String newText, Player editor) {
        editMessageVisual(messageId, newText, Component.text(newText), editor);
    }

    public void editMessageVisual(int messageId, String newText, Component newTextComponent, Player editor) {
        MessageData data = getMessageDataCache().getIfPresent(messageId);
        if (data == null) return;

        boolean useIncomingComponent = !Component.text(newText).equals(newTextComponent);
        me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEditEvent editEvent =
                new me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEditEvent(
                        editor,
                        messageId,
                        data.rawText(),
                        Component.text(data.rawText()),
                        newText,
                        newTextComponent
                );
        Bukkit.getPluginManager().callEvent(editEvent);

        if (editEvent.isCancelled()) {
            sendMessage(editor, "edit-cancelled");
            return;
        }

        String finalText = editEvent.getNewMessage();
        Component finalMessageComponent = null;
        if (editEvent.hasNewMessageComponentChanged() || (useIncomingComponent && finalText.equals(newText))) {
            finalMessageComponent = editEvent.getNewMessageComponent();
        }
        databaseManager.updateMessage(messageId, finalText);
        getMessageDataCache().put(messageId, new MessageData(data.owner(), data.channel(), finalText, data.isStaff()));

        String activeChannel = data.channel();
        var channels = getConfig().getConfigurationSection("colors.channels");
        String format = channels != null ? channels.getString(activeChannel + ".format", getConfig().getString("chat.default-format", "<player>: <message>")) : "<player>: <message>";

        OfflinePlayer offlineOwner = Bukkit.getOfflinePlayer(data.owner());
        String ownerName = offlineOwner.getName() != null ? offlineOwner.getName() : "Unknown";

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            format = PlaceholderAPI.setPlaceholders(offlineOwner, format);
        }
        format = stripPlayerHoverClick(format);

        if (finalMessageComponent == null && !editor.hasPermission("lovechat.color")) {
            finalText = MiniMessage.miniMessage().escapeTags(finalText);
        }

        String hoverText = getConfig().getString("colors.hover.player-hover", "<gray>Инфо</gray>").replace("{player}", ownerName);
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            hoverText = PlaceholderAPI.setPlaceholders(offlineOwner, hoverText);
        }
        hoverText = hoverText.replace("\n", "<br>");

        List<String> clickCmds = getConfig().getStringList("colors.hover.click-command");
        String clickCmd = clickCmds.isEmpty() ? "/msg {player} " : clickCmds.getFirst().replace("{player}", ownerName);

        // Создаём компонент игрока: голова + имя с hover/click
        // Пытаемся получить кастомный скин из CMI
        me.lovelace.lovechat.depends.CMISkinUtil.SkinProperty skinProp =
                me.lovelace.lovechat.depends.CMISkinUtil.getSkinProperty(offlineOwner.getUniqueId(), ownerName);
        String skinSignature = skinProp != null ? skinProp.signature() : null;
        Component headComponent = HeadComponentUtil.createHeadComponent(
                offlineOwner.getUniqueId(),
                ownerName,
                skinProp != null ? skinProp.value() : null,
                skinSignature
        );

        if (headComponent == null && skinProp != null && skinProp.value() != null && !skinProp.value().isEmpty()) {
            String propertiesJson;
            if (skinProp.signature() != null && !skinProp.signature().isEmpty()) {
                propertiesJson = "\"properties\":[{\"name\":\"textures\",\"value\":\"" + skinProp.value()
                        + "\",\"signature\":\"" + skinProp.signature() + "\"}]";
            } else {
                propertiesJson = "\"properties\":[{\"name\":\"textures\",\"value\":\"" + skinProp.value() + "\"}]";
            }
            String fakeUuid = String.valueOf(HeadComponentUtil.makeSkinUuid(ownerName));
            String fakeName = HeadComponentUtil.makeSkinName(ownerName);
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + fakeUuid
                    + "\",\"name\":\"" + fakeName + "\"," + propertiesJson + "}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        } else if (headComponent == null) {
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + offlineOwner.getUniqueId()
                    + "\",\"name\":\"" + ownerName + "\"}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        }

        // Имя игрока
        Component playerDisplay = Component.text(ownerName).font(Key.key("minecraft:default"));

        // Полный компонент: голова + пробел + имя с hover/click
        Component interactivePlayer = Component.empty()
                .append(headComponent)
                .append(Component.text(" "))
                .append(playerDisplay)
                .hoverEvent(HoverEvent.showText(MiniMessage.miniMessage().deserialize(hoverText)))
                .clickEvent(ClickEvent.runCommand(clickCmd));

        String editPrefix = "";
        if (getConfig().getBoolean("messageedit.enabled", true)) {
            editPrefix = " <hover:show_text:'<yellow>Изменить'><click:run_command:'/medit " + messageId + "'>" + getConfig().getString("messageedit.prefix", "<yellow>[✎]</yellow>") + "</click></hover>";
        }
        String delPrefix = "";
        if (getConfig().getBoolean("messagedelete.enabled", true)) {
            delPrefix = "<hover:show_text:'<red>Удалить'><click:run_command:'/md " + messageId + "'>" + getConfig().getString("messagedelete.prefix", "<dark_gray>[<red>x</red>]</dark_gray>") + "</click></hover>";
        }

        String editedMark = getConfig().getString("messageedit.mark", " <dark_gray><i>(изм.)</i></dark_gray>");
        // Порядок: сначала редактировать, потом удалить
        String buttons = editPrefix + delPrefix;

        // Убираем <delete_edit_buttons> из формата
        format = format.replace("<delete_edit_buttons>", "");

        Component messageComponent = finalMessageComponent != null
                ? finalMessageComponent
                : MiniMessage.miniMessage().deserialize(finalText);

        Component baseComponent = MiniMessage.miniMessage().deserialize(format + editedMark,
                Placeholder.component("player", interactivePlayer),
                Placeholder.component("message", messageComponent)
        );

        UUID ownerUuid = data.owner();

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (isWorldDisabled(p.getWorld().getName())) {
                continue;
            }
            List<ChatLine> history = chatHistory.getIfPresent(p.getUniqueId());
            if (history == null) continue;

            boolean found = false;
            List<ChatLine> toSend = new ArrayList<>();

            // Добавляем кнопки только автору сообщения или админам
            boolean isOwner = p.getUniqueId().equals(ownerUuid);
            boolean isAdmin = p.hasPermission("lovechat.admin") || p.hasPermission("lovechat.moderation");

            // Кнопки добавляются ПОСЛЕ сообщения
            Component newComponent;
            if (isOwner || isAdmin) {
                newComponent = baseComponent.append(MiniMessage.miniMessage().deserialize(buttons));
            } else {
                newComponent = baseComponent;
            }

            synchronized (history) {
                for (int i = 0; i < history.size(); i++) {
                    ChatLine cl = history.get(i);
                    if (cl.messageId() == messageId && !cl.isPluginMessage()) {
                        found = true;
                        history.set(i, new ChatLine(messageId, newComponent, false));
                        break;
                    }
                }
                if (found) {
                    toSend.addAll(history);
                }
            }

            if (found) {
                redrawIgnoreCache.put(p.getUniqueId(), true);
                p.getScheduler().run(this, scheduledTask -> {
                    p.sendMessage(clearChatComponent);
                    for (ChatLine line : toSend) {
                        p.sendMessage(line.component());
                    }
                }, null);
            }
        }
        sendMessage(editor, "edit-success");
    }

    public void startEditSession(Player player, int messageId, String oldText) {
        activeEditSessions.put(player.getUniqueId(), new EditSession(messageId, oldText));

        Component promptMsg = MiniMessage.miniMessage().deserialize(getRawMsg("edit-prompt"));
        player.sendMessage(promptMsg);

        Component oldMsg = MiniMessage.miniMessage().deserialize(
                "<dark_gray>Старое сообщение: </dark_gray><white><click:suggest_command:'" + oldText.replace("'", "") + "'><hover:show_text:'Нажмите, чтобы скопировать в строку ввода'>" + oldText + "</hover></click></white>"
        );
        player.sendMessage(oldMsg);
    }

    public @Nullable EditSession getEditSession(UUID uuid) { return activeEditSessions.get(uuid); }
    public void removeEditSession(UUID uuid) { activeEditSessions.remove(uuid); }

    private static String stripPlayerHoverClick(String format) {
        if (format == null || format.isEmpty()) return format;
        String out = format;
        out = out.replaceAll("(?is)<hover:[^>]*>\\s*<click:[^>]*>\\s*<player>\\s*</click>\\s*</hover>", "<player>");
        out = out.replaceAll("(?is)<click:[^>]*>\\s*<hover:[^>]*>\\s*<player>\\s*</hover>\\s*</click>", "<player>");
        out = out.replaceAll("(?is)<hover:[^>]*>\\s*<player>\\s*</hover>", "<player>");
        out = out.replaceAll("(?is)<click:[^>]*>\\s*<player>\\s*</click>", "<player>");
        return out;
    }
}
