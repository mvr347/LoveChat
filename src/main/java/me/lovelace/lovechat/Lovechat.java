package me.lovelace.lovechat;

import me.lovelace.lovechat.commands.LoveChatAdminCommand;
import me.lovelace.lovechat.depends.CMISkinUtil;
import me.lovelace.lovechat.depends.ProtocolLibHook;
import me.lovelace.lovechat.expansion.ChatPlaceholderExpansion;
import me.lovelace.lovechat.listeners.ChatListener;
import me.lovelace.lovechat.managers.*;
import me.lovelace.lovechat.render.MessageRenderer;
import com.google.common.cache.Cache;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
import java.util.concurrent.TimeUnit;

/**
 * Основной класс плагина Lovechat.
 * Строго оптимизирован под Paper API 1.21.11 (совместим с Folia) и Java 21.
 * <p>
 * Это composition root: класс создаёт и связывает менеджеры ({@link EditSessionManager},
 * {@link ChatHistoryManager}, {@link MessageRenderer}, {@link DatabaseManager},
 * {@link ChatBubbleManager}) и регистрирует команды/слушатели, но сам не содержит бизнес-логики
 * редактирования, истории чата или рендера сообщений — раньше это всё было здесь одним
 * god-object классом (см. код-ревью, пункт 3).
 */
@SuppressWarnings({"DuplicatedCode", "RedundantReturnStatement"})
public final class Lovechat extends JavaPlugin {

    private static Lovechat instance;
    private DatabaseManager databaseManager;
    private ChatBubbleManager chatBubbleManager;
    private ChatPlaceholderExpansion placeholderExpansion;

    private EditSessionManager editSessionManager;
    private ChatHistoryManager chatHistoryManager;
    private MessageRenderer messageRenderer;

    private YamlConfiguration messagesConfig;

    private final Map<String, Integer> customChannels = new ConcurrentHashMap<>();
    private final Map<UUID, String> defaultChannels = new ConcurrentHashMap<>();
    private final Set<UUID> silentPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Set<UUID>> ignoredPlayers = new ConcurrentHashMap<>();
    private final Set<UUID> tagsDisabledPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> spyPlayers = ConcurrentHashMap.newKeySet();

    /** Cached lowercase copy of general.disabled-worlds - isWorldDisabled() runs on every
     *  chat message, so it can't afford to call getConfig().getStringList() and build a new
     *  List each time. Refreshed in onEnable() and on every "reload config"/"reload all". */
    private volatile Set<String> disabledWorlds = Set.of();

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
        loadDisabledWorlds();

        databaseManager = new DatabaseManager(this);
        databaseManager.init();

        CMISkinUtil.init();

        chatBubbleManager = new ChatBubbleManager(this);
        messageRenderer = new MessageRenderer(this);
        editSessionManager = new EditSessionManager(this);
        chatHistoryManager = new ChatHistoryManager(this, messageRenderer);

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

        registerSafeCommand("lovechatadmin", new LoveChatAdminCommand(this));

        registerDynamicChannelCommands();

        long runEveryTicks = 20L * 60L * 10L;
        long deleteOlderThanMillis = TimeUnit.HOURS.toMillis(1);

        Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> {
                    databaseManager.cleanOldMessages(deleteOlderThanMillis);
                    chatHistoryManager.cleanUp();
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

    // ========================================================== //
    //                    ДОСТУП К МЕНЕДЖЕРАМ                      //
    // ========================================================== //

    public @NotNull DatabaseManager getDatabaseManager() { return databaseManager; }
    public @NotNull ChatBubbleManager getChatBubbleManager() { return chatBubbleManager; }
    public @NotNull EditSessionManager getEditSessionManager() { return editSessionManager; }
    public @NotNull ChatHistoryManager getChatHistoryManager() { return chatHistoryManager; }
    public @NotNull MessageRenderer getMessageRenderer() { return messageRenderer; }

    // ---- Тонкие делегаты к ChatHistoryManager (оставлены на главном классе, чтобы не менять
    //      сигнатуры вызовов в ChatListener/CommandManager/LovechatAPI — сама логика уже вынесена) ----

    public int getNextMessageId() { return chatHistoryManager.getNextMessageId(); }
    public void setLastMessageId(@NotNull UUID uuid, int messageId) { chatHistoryManager.setLastMessageId(uuid, messageId); }
    public @Nullable Integer getLastMessageId(@NotNull UUID uuid) { return chatHistoryManager.getLastMessageId(uuid); }
    public @NotNull Cache<Integer, ChatHistoryManager.MessageData> getMessageDataCache() { return chatHistoryManager.getMessageDataCache(); }

    public void addChatLineAndSend(@NotNull Player player, int messageId, @NotNull Component component) {
        chatHistoryManager.addChatLineAndSend(player, messageId, component);
    }

    public @NotNull Component getClearChatComponent() { return chatHistoryManager.getClearChatComponent(); }
    public void clearChatForPlayer(@NotNull Player player, boolean keepStaff) { chatHistoryManager.clearChatForPlayer(player, keepStaff); }

    public boolean shouldIgnorePacket(UUID uuid, String plainText) { return chatHistoryManager.shouldIgnorePacket(uuid, plainText); }
    public void recordSystemMessageFromPacket(Player player, Component component) { chatHistoryManager.recordSystemMessageFromPacket(player, component); }

    public void deleteMessageVisual(int messageId, @NotNull org.bukkit.command.CommandSender sender) {
        chatHistoryManager.deleteMessageVisual(messageId, sender);
    }

    public void editMessageVisual(int messageId, String newText, Player editor) {
        chatHistoryManager.editMessageVisual(messageId, newText, editor);
    }

    public void editMessageVisual(int messageId, String newText, Component newTextComponent, Player editor) {
        chatHistoryManager.editMessageVisual(messageId, newText, newTextComponent, editor);
    }

    // ---- Тонкие делегаты к EditSessionManager ----

    public void startEditSession(Player player, int messageId, String oldText) {
        editSessionManager.startEditSession(player, messageId, oldText);
    }

    public @Nullable EditSessionManager.EditSession getEditSession(UUID uuid) { return editSessionManager.getSession(uuid); }
    public void removeEditSession(UUID uuid) { editSessionManager.removeSession(uuid); }

    // ---- Состояние игрока (каналы/silent/ignore/tags/spy) — лёгкие переключатели без бизнес-логики,
    //      не выделялись в отдельный менеджер, т.к. явно не входили в список god-object проблем ревью ----

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
    public int getIgnoredCount(@NotNull UUID uuid) { Set<UUID> ignoredSet = ignoredPlayers.get(uuid); return ignoredSet == null ? 0 : ignoredSet.size(); }
    public void toggleIgnore(@NotNull UUID ignorer, @NotNull UUID ignored) {
        Set<UUID> ignoredSet = ignoredPlayers.computeIfAbsent(ignorer, k -> ConcurrentHashMap.newKeySet());
        if (!ignoredSet.remove(ignored)) {
            ignoredSet.add(ignored);
            databaseManager.addIgnore(ignorer, ignored);
        } else {
            databaseManager.removeIgnore(ignorer, ignored);
        }
    }
    public void loadIgnores(@NotNull UUID uuid, @NotNull Set<UUID> ignores) { ignoredPlayers.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet()).addAll(ignores); }
    /** Drops the in-memory ignore-list cache for a player on quit; the underlying relationships
     *  stay in the database and are reloaded via {@link #loadIgnores} on their next join. Without
     *  this, ignoredPlayers grows by one entry for every unique player who's ever used /ignorechat,
     *  forever, for the life of the server. */
    public void clearIgnoredMemory(@NotNull UUID uuid) { ignoredPlayers.remove(uuid); }
    public void loadDisabledWorlds() {
        List<String> disabled = getConfig().getStringList("general.disabled-worlds");
        Set<String> lowered = new HashSet<>();
        for (String w : disabled) {
            if (w != null) lowered.add(w.toLowerCase(Locale.ROOT));
        }
        disabledWorlds = lowered;
    }

    public boolean isWorldDisabled(@NotNull String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
