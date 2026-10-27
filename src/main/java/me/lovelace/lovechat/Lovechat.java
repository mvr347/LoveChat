package me.lovelace.lovechat;

import me.lovelace.lovechat.commands.LoveChatAdminCommand;
import me.lovelace.lovechat.depends.CMISkinUtil;
import me.lovelace.lovechat.depends.NettyChatHook;
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
 * Это composition root: класс создаёт и связывает менеджеры ({@link MessageActions},
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

    private NettyChatHook chatHook;
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
        chatHistoryManager = new ChatHistoryManager(this, messageRenderer);
        chatHistoryManager.seedMessageIdCounter(databaseManager.getMaxMessageIdAtStartup() + 1);

        getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        getServer().getPluginManager().registerEvents(new MessageActions(this), this);
        chatHook = new NettyChatHook(this);
        for (Player online : Bukkit.getOnlinePlayers()) chatHook.inject(online);
        registerPlaceholderExpansion();

        CommandManager cmdManager = new CommandManager(this);
        registerSafeCommand("lovechat", cmdManager);
        registerSafeCommand("channel", cmdManager);
        registerSafeCommand("ignorechat", cmdManager);
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
        if (chatHook != null) chatHook.uninjectAll();

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

    /** Резолвит ключ из messages.yml в компонент; {@code {key}}-плейсхолдеры подставляются как простой текст. */
    private @NotNull Component buildMessage(@NotNull String key, boolean withPrefix, @NotNull String... placeholders) {
        if (messagesConfig == null) loadMessages();
        String prefix = withPrefix ? messagesConfig.getString("prefix", "") : "";
        Component component = MiniMessage.miniMessage().deserialize(prefix + getRawMsg(key));

        for (int i = 0; i < placeholders.length; i += 2) {
            if (i + 1 < placeholders.length) {
                final String target = placeholders[i].replace("{", "").replace("}", "");
                final String replacement = placeholders[i + 1];
                component = component.replaceText(builder -> builder.matchLiteral(target).replacement(replacement));
            }
        }
        return component;
    }

    public void sendMessage(@NotNull org.bukkit.command.CommandSender sender, @NotNull String key, @NotNull String... placeholders) {
        sender.sendMessage(buildMessage(key, true, placeholders));
    }

    /**
     * Короткое уведомление об отказе («нет прав», «сообщение устарело»). Игроку оно показывается в
     * action bar и не попадает в чат, а значит не засоряет историю и не вызывает перерисовку;
     * консоли и прочим отправителям — обычной строкой.
     */
    public void notice(@NotNull org.bukkit.command.CommandSender sender, @NotNull String key, @NotNull String... placeholders) {
        if (sender instanceof Player player) {
            player.sendActionBar(buildMessage(key, false, placeholders));
        } else {
            sendMessage(sender, key, placeholders);
        }
    }

    /** Like {@link #getRawMsg(String)}, but a missing key yields {@code fallback}: messages.yml of an existing server is never overwritten. */
    public @NotNull String getRawMsg(@NotNull String key, @NotNull String fallback) {
        if (messagesConfig == null) loadMessages();
        String msg = messagesConfig.getString(key);
        return msg == null ? fallback : msg;
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
    public @Nullable NettyChatHook getChatHook() { return chatHook; }
    public @NotNull ChatHistoryManager getChatHistoryManager() { return chatHistoryManager; }
    public @NotNull MessageRenderer getMessageRenderer() { return messageRenderer; }

    // ---- Тонкие делегаты к ChatHistoryManager (оставлены на главном классе, чтобы не менять
    //      сигнатуры вызовов в ChatListener/CommandManager/LovechatAPI — сама логика уже вынесена) ----

    public int getNextMessageId() { return chatHistoryManager.getNextMessageId(); }
    public void setLastMessageId(@NotNull UUID uuid, int messageId) { chatHistoryManager.setLastMessageId(uuid, messageId); }
    public @Nullable Integer getLastMessageId(@NotNull UUID uuid) { return chatHistoryManager.getLastMessageId(uuid); }
    public void removeLastMessageId(@NotNull UUID uuid) { chatHistoryManager.removeLastMessageId(uuid); }
    public @NotNull Cache<Integer, ChatHistoryManager.MessageData> getMessageDataCache() { return chatHistoryManager.getMessageDataCache(); }

    public void addChatLineAndSend(@NotNull Player player, int messageId, @NotNull Component component) {
        chatHistoryManager.addChatLineAndSend(player, messageId, component);
    }

    public void removePluginEchoes(int messageId) { chatHistoryManager.removePluginEchoes(messageId); }
    public @NotNull Component getClearChatComponent() { return chatHistoryManager.getClearChatComponent(); }
    public void clearChatForPlayer(@NotNull Player player, boolean keepStaff) { chatHistoryManager.clearChatForPlayer(player, keepStaff); }

    public boolean consumeOwnEcho(UUID uuid, String plainText) { return chatHistoryManager.consumeOwnEcho(uuid, plainText); }
    public void recordSystemMessageFromPacket(UUID uuid, Component component) { chatHistoryManager.recordSystemMessageFromPacket(uuid, component); }

    public void deleteMessageVisual(int messageId, @NotNull org.bukkit.command.CommandSender sender) {
        chatHistoryManager.deleteMessageVisual(messageId, sender);
    }

    public void editMessageVisual(int messageId, String newText, Player editor) {
        chatHistoryManager.editMessageVisual(messageId, newText, editor);
    }

    public void editMessageVisual(int messageId, String newText, Component newTextComponent, Player editor) {
        chatHistoryManager.editMessageVisual(messageId, newText, newTextComponent, editor);
    }

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
    public void toggleTagsDisabled(@NotNull UUID uuid) {
        if (!tagsDisabledPlayers.remove(uuid)) tagsDisabledPlayers.add(uuid);
        // Saved on every change: the cache is reloaded from the database on join, so an unsaved toggle used to be lost.
        databaseManager.saveTagsDisabled(uuid, tagsDisabledPlayers.contains(uuid));
    }
    public boolean isIgnoring(@NotNull UUID ignorer, @NotNull UUID ignored) {
        var shared = me.lovelace.lovechat.depends.SharedIgnore.find();
        if (shared != null && shared.isIgnoring(ignorer, ignored)) return true;
        return isIgnoringLocally(ignorer, ignored);
    }
    private boolean isIgnoringLocally(@NotNull UUID ignorer, @NotNull UUID ignored) { Set<UUID> ignoredSet = ignoredPlayers.get(ignorer); return ignoredSet != null && ignoredSet.contains(ignored); }
    /**
     * Moves a player's old LoveChat list into the shared one. Entries that cannot be imported yet
     * stay in the table and are retried on the next join.
     */
    public void migrateIgnores(@NotNull org.bukkit.entity.Player player, @NotNull Set<UUID> ignores) {
        var shared = me.lovelace.lovechat.depends.SharedIgnore.find();
        if (shared == null || ignores.isEmpty()) return;
        // Each entry leaves the local list on its own once imported; only failures wait for the next join.
        for (UUID target : ignores) {
            // Skip entries unignored while the migration was waiting.
            if (!isIgnoringLocally(player.getUniqueId(), target)) continue;
            if (!shared.importIgnore(player, target)) continue;
            Set<UUID> local = ignoredPlayers.get(player.getUniqueId());
            if (local != null) local.remove(target);
            databaseManager.removeIgnore(player.getUniqueId(), target);
        }
    }
    public int getIgnoredCount(@NotNull UUID uuid) { Set<UUID> ignoredSet = ignoredPlayers.get(uuid); return ignoredSet == null ? 0 : ignoredSet.size(); }
    public void toggleIgnore(@NotNull UUID ignorer, @NotNull UUID ignored) {
        // Once the shared list exists it is the one to change, or a migrated entry could never be removed.
        var shared = me.lovelace.lovechat.depends.SharedIgnore.find();
        var online = org.bukkit.Bukkit.getPlayer(ignorer);
        if (shared != null && online != null) {
            boolean currently = shared.isIgnoring(ignorer, ignored) || isIgnoringLocally(ignorer, ignored);
            if (shared.setIgnored(online, ignored, !currently)) {
                // Whatever the old list held is now represented by the shared one.
                Set<UUID> local = ignoredPlayers.get(ignorer);
                if (local != null) local.remove(ignored);
                databaseManager.removeIgnore(ignorer, ignored);
                return;
            }
        }
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
        // The packet hook keeps a per-player copy of "my world is disabled" (it must not call Bukkit on Netty threads).
        if (chatHook != null) {
            for (Player online : Bukkit.getOnlinePlayers()) chatHook.refreshWorld(online);
        }
    }

    public boolean isWorldDisabled(@NotNull String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
