package me.lovelace.lovechat.api;

import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.managers.ChatBubbleManager;
import me.lovelace.lovechat.managers.DatabaseManager;
import me.lovelace.lovechat.depends.CMISkinUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * <h1>Lovechat API</h1>
 *
 * <p>Основной класс для доступа к API плагина Lovechat.</p>
 *
 * <h2>Содержание:</h2>
 * <ul>
 *     <li><a href="#getting-started">Начало работы</a></li>
 *     <li><a href="#chat-api">Chat API</a></li>
 *     <li><a href="#chat-bubbles">ChatBubbles API</a></li>
 *     <li><a href="#database">Database API</a></li>
 *     <li><a href="#cmi-skins">CMI Skin Support</a></li>
 *     <li><a href="#events">События (Events)</a></li>
 *     <li><a href="#examples">Примеры использования</a></li>
 * </ul>
 *
 * <a id="getting-started"></a>
 * <h2>Начало работы</h2>
 *
 * <p>Для получения экземпляра Lovechat используйте:</p>
 * <pre>{@code
 * Lovechat chat = Lovechat.getInstance();
 * }</pre>
 *
 * <p>Или через LovechatAPI:</p>
 * <pre>{@code
 * Lovechat chat = LovechatAPI.getLovechat();
 * }</pre>
 *
 * @author Lovelace
 * @version 2.6
 * @since 1.0
 */
@SuppressWarnings({"unused", "InstantiationOfUtilityClass"})
public class LovechatAPI {

    private static LovechatAPI instance;

    private LovechatAPI() {
        // Приватный конструктор для синглтона
    }

    /**
     * Получить экземпляр LovechatAPI
     * @return LovechatAPI instance
     */
    @NotNull
    public static LovechatAPI getInstance() {
        if (instance == null) {
            instance = new LovechatAPI();
        }
        return instance;
    }

    /**
     * Получить экземпляр Lovechat
     * @return Lovechat instance
     */
    @NotNull
    public static Lovechat getLovechat() {
        return Lovechat.getInstance();
    }

    /**
     * Получить ChatBubbleManager
     * @return ChatBubbleManager instance
     */
    @NotNull
    public static ChatBubbleManager getBubbleManager() {
        return Lovechat.getInstance().getChatBubbleManager();
    }

    /**
     * Получить DatabaseManager
     * @return DatabaseManager instance
     */
    @NotNull
    public static DatabaseManager getDatabaseManager() {
        return Lovechat.getInstance().getDatabaseManager();
    }

    /**
    /**
    /**
     * Получить утилиту CMI скинов
     * @return CMISkinUtil class
     */
    @NotNull
    public static Class<?> getCMISkinUtil() {
        return CMISkinUtil.class;
    }

    // ========================================== //
    //              CHAT API                      //
    // ========================================== //

    /**
     * Получить канал по умолчанию для игрока
     * @param uuid UUID игрока
     * @return Канал по умолчанию
     */
    @NotNull
    public static String getDefaultChannel(@NotNull UUID uuid) {
        return Lovechat.getInstance().getDefaultChannel(uuid);
    }

    /**
     * Установить канал по умолчанию для игрока
     * @param uuid UUID игрока
     * @param channel Канал
     */
    public static void setDefaultChannel(@NotNull UUID uuid, @NotNull String channel) {
        Lovechat.getInstance().setDefaultChannel(uuid, channel);
    }

    /**
     * Получить последний messageId игрока
     * @param uuid UUID игрока
     * @return messageId или null
     */
    @Nullable
    public static Integer getLastMessageId(@NotNull UUID uuid) {
        return Lovechat.getInstance().getLastMessageId(uuid);
    }

    /**
     * Проверить игрока на silent режим
     * @param uuid UUID игрока
     * @return true если silent включён
     */
    public static boolean isSilent(@NotNull UUID uuid) {
        return Lovechat.getInstance().isSilent(uuid);
    }

    /**
     * Переключить silent режим
     * @param uuid UUID игрока
     */
    public static void toggleSilent(@NotNull UUID uuid) {
        Lovechat.getInstance().toggleSilent(uuid);
    }

    /**
     * Проверить игрока на spy режим
     * @param uuid UUID игрока
     * @return true если spy включён
     */
    public static boolean isSpy(@NotNull UUID uuid) {
        return Lovechat.getInstance().isSpy(uuid);
    }

    /**
     * Переключить spy режим
     * @param uuid UUID игрока
     */
    public static void toggleSpy(@NotNull UUID uuid) {
        Lovechat.getInstance().toggleSpy(uuid);
    }

    /**
     * Проверить игнор
     * @param ignorerUUID Кто игнорирует
     * @param ignoredUUID Кого игнорируют
     * @return true если игнорирует
     */
    public static boolean isIgnoring(@NotNull UUID ignorerUUID, @NotNull UUID ignoredUUID) {
        return Lovechat.getInstance().isIgnoring(ignorerUUID, ignoredUUID);
    }

    /**
     * Переключить игнор
     * @param ignorerUUID Кто игнорирует
     * @param ignoredUUID Кого игнорируют
     */
    public static void toggleIgnore(@NotNull UUID ignorerUUID, @NotNull UUID ignoredUUID) {
        Lovechat.getInstance().toggleIgnore(ignorerUUID, ignoredUUID);
    }

    /**
     * Очистить чат игроку
     * @param player Игрок
     * @param keepStaff Сохранить staff bar
     */
    public static void clearChatForPlayer(@NotNull Player player, boolean keepStaff) {
        Lovechat.getInstance().clearChatForPlayer(player, keepStaff);
    }

    // ========================================== //
    //           CMISKINUTIL API                  //
    // ========================================== //

    /**
     * Проверка доступности CMI
     * @return true если CMI доступен
     */
    public static boolean isCMIAvailable() {
        return CMISkinUtil.isCMIAvailable();
    }

    /**
     * Получить голову игрока со скином
     * @param player Игрок
     * @return ItemStack головы
     */
    @NotNull
    public static org.bukkit.inventory.ItemStack getPlayerHead(@NotNull Player player) {
        return CMISkinUtil.getPlayerHead(player);
    }

    /**
     * Получить текстуру скина
     * @param uuid UUID игрока
     * @return Текстура или null
     */
    @Nullable
    public static String getSkinTexture(@NotNull UUID uuid) {
        return CMISkinUtil.getSkinTexture(uuid);
    }

    /**
     * Получить текстуру скина в Base64
     * @param uuid UUID игрока
     * @return Base64 текстура или null
     */
    @Nullable
    public static String getSkinTextureBase64(@NotNull UUID uuid) {
        return CMISkinUtil.getSkinTextureBase64(uuid);
    }

    /**
     * Создать JSON компонент головы
     * @param playerName Имя игрока
     * @param uuid UUID игрока
     * @return JSON строка
     */
    @NotNull
    public static String createHeadJsonWithSkin(@NotNull String playerName, @NotNull UUID uuid) {
        return CMISkinUtil.createHeadJsonWithSkin(playerName, uuid);
    }

    /**
     * Очистить кэш скинов
     */
    public static void clearCache() {
        CMISkinUtil.clearCache();
    }

    /**
     * Удалить скин из кэша
     * @param uuid UUID игрока
     */
    public static void removeFromCache(@NotNull UUID uuid) {
        CMISkinUtil.removeFromCache(uuid);
    }

    /**
     * Инициализация CMI
     */
    public static void initCMI() {
        CMISkinUtil.init();
    }

    // ========================================== //
    //           CHAT BUBBLES API                 //
    // ========================================== //

    /**
     * Показать голограмму над головой игрока
     * @param player Игрок
     * @param message Сообщение
     * @param channel Канал
     */
    public static void showBubble(@NotNull Player player, @NotNull String message, @NotNull String channel) {
        Lovechat.getInstance().getChatBubbleManager().showBubble(player, message, channel);
    }

    /**
     * Удалить голограмму игрока
     * @param uuid UUID игрока
     */
    public static void removeBubble(@NotNull UUID uuid) {
        Lovechat.getInstance().getChatBubbleManager().removeBubble(uuid);
    }

    /**
     * Очистить все голограммы
     */
    public static void clearAllBubbles() {
        Lovechat.getInstance().getChatBubbleManager().clearAll();
    }

    /**
     * Проверка включены ли ChatBubbles
     * @return true если включены
     */
    public static boolean isBubblesEnabled() {
        return Lovechat.getInstance().getChatBubbleManager().isEnabled();
    }

    // ========================================== //
    //            DATABASE API                    //
    // ========================================== //

    /**
     * Логирование сообщения
     * @param messageId ID сообщения
     * @param playerUUID UUID игрока
     * @param messageText Текст сообщения
     */
    public static void logMessage(int messageId, @NotNull UUID playerUUID, @NotNull String messageText) {
        Lovechat.getInstance().getDatabaseManager().logMessage(messageId, playerUUID, messageText);
    }

    /**
     * Удаление сообщения
     * @param messageId ID сообщения
     */
    public static void deleteMessage(int messageId) {
        Lovechat.getInstance().getDatabaseManager().deleteMessage(messageId);
    }

    /**
     * Редактирование сообщения
     * @param messageId ID сообщения
     * @param newText Новый текст
     */
    public static void editMessage(int messageId, @NotNull String newText) {
        Lovechat.getInstance().getDatabaseManager().updateMessage(messageId, newText);
    }

    /**
     * Обновление сообщения
     * @param messageId ID сообщения
     * @param newText Новый текст
     */
    public static void updateMessage(int messageId, @NotNull String newText) {
        Lovechat.getInstance().getDatabaseManager().updateMessage(messageId, newText);
    }

    /**
     * Получить количество сообщений игрока
     * @param playerUUID UUID игрока
     * @return CompletableFuture с количеством сообщений
     */
    @NotNull
    public static CompletableFuture<Integer> getMessageCount(@NotNull UUID playerUUID) {
        return Lovechat.getInstance().getDatabaseManager().getMessageCount(playerUUID);
    }

    /**
     * Инкремент счётчика сообщений
     * @param playerUUID UUID игрока
     */
    public static void incrementMessageCount(@NotNull UUID playerUUID) {
        Lovechat.getInstance().getDatabaseManager().incrementMessageCount(playerUUID);
    }

    /**
     * Добавить в игнор
     * @param whoUUID Кто игнорирует
     * @param targetUUID Кого игнорируют
     */
    public static void addIgnore(@NotNull UUID whoUUID, @NotNull UUID targetUUID) {
        Lovechat.getInstance().getDatabaseManager().addIgnore(whoUUID, targetUUID);
    }

    /**
     * Удалить из игнора
     * @param whoUUID Кто игнорирует
     * @param targetUUID Кого игнорируют
     */
    public static void removeIgnore(@NotNull UUID whoUUID, @NotNull UUID targetUUID) {
        Lovechat.getInstance().getDatabaseManager().removeIgnore(whoUUID, targetUUID);
    }

    /**
     * Получить список игнорируемых
     * @param playerUUID UUID игрока
     * @return CompletableFuture с списком игнорируемых
     */
    @NotNull
    public static CompletableFuture<Set<UUID>> getIgnores(@NotNull UUID playerUUID) {
        return Lovechat.getInstance().getDatabaseManager().getIgnores(playerUUID);
    }

    /**
     * Очистить список игнора
     * @param playerUUID UUID игрока
     */
    public static void clearIgnores(@NotNull UUID playerUUID) {
        Lovechat.getInstance().getDatabaseManager().clearIgnores(playerUUID);
    }

    /**
     * Сохранить канал по умолчанию
     * @param playerUUID UUID игрока
     * @param channel Канал
     */
    public static void saveDefaultChannel(@NotNull UUID playerUUID, @NotNull String channel) {
        Lovechat.getInstance().getDatabaseManager().saveDefaultChannel(playerUUID, channel);
    }

    /**
     * Получить канал по умолчанию
     * @param playerUUID UUID игрока
     * @return CompletableFuture с каналом
     */
    @NotNull
    public static CompletableFuture<String> getDefaultChannelAsync(@NotNull UUID playerUUID) {
        return Lovechat.getInstance().getDatabaseManager().getDefaultChannel(playerUUID);
    }

    /**
     * Очистить канал по умолчанию
     * @param playerUUID UUID игрока
     */
    public static void clearDefaultChannel(@NotNull UUID playerUUID) {
        Lovechat.getInstance().getDatabaseManager().clearDefaultChannel(playerUUID);
    }

    /**
     * Сохранить статус отключенных тегов
     * @param playerUUID UUID игрока
     * @param disabled Статус
     */
    public static void saveTagsDisabled(@NotNull UUID playerUUID, boolean disabled) {
        Lovechat.getInstance().getDatabaseManager().saveTagsDisabled(playerUUID, disabled);
    }

    /**
     * Получить статус отключенных тегов
     * @param playerUUID UUID игрока
     * @return CompletableFuture со статусом
     */
    @NotNull
    public static CompletableFuture<Boolean> getTagsDisabledAsync(@NotNull UUID playerUUID) {
        return Lovechat.getInstance().getDatabaseManager().getTagsDisabled(playerUUID);
    }

    /**
     * Очистить статус отключенных тегов
     * @param playerUUID UUID игрока
     */
    public static void clearTagsDisabled(@NotNull UUID playerUUID) {
        Lovechat.getInstance().getDatabaseManager().clearTagsDisabled(playerUUID);
    }

    /**
     * Очистить все сообщения
     */
    public static void clearAllMessages() {
        Lovechat.getInstance().getDatabaseManager().clearAllMessagesSync();
    }

    /**
     * Очистить старые сообщения
     * @param olderThanMillis Время в миллисекундах
     */
    public static void cleanOldMessages(long olderThanMillis) {
        Lovechat.getInstance().getDatabaseManager().cleanOldMessages(olderThanMillis);
    }
    // ========================================== //
    //              EVENTS API                    //
    // ========================================== //

    @SuppressWarnings("unused")
    public static class LovechatDeleteEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final int messageId;
        private final CommandSender deleter;

        public LovechatDeleteEvent(int messageId, CommandSender deleter) {
            this.messageId = messageId;
            this.deleter = deleter;
        }

        public int getMessageId() { return messageId; }
        public CommandSender getDeleter() { return deleter; }

        @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @SuppressWarnings("unused")
    public static class LovechatMentionEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player sender;
        private final Player mentioned;

        public LovechatMentionEvent(Player sender, Player mentioned) {
            // Событие летит и с асинхронного потока чата, и с планировщика сущности
            // (главный поток). Хардкод super(true) роняет сервер во втором случае —
            // сообщаем реальный поток, а не предполагаемый.
            super(!Bukkit.isPrimaryThread());
            this.sender = sender;
            this.mentioned = mentioned;
        }

        public Player getSender() { return sender; }
        public Player getMentioned() { return mentioned; }

        @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @SuppressWarnings("unused")
    public static class LovechatMessageEditEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final int messageId;
        private final String oldMessage;
        private final Component oldMessageComponent;
        private String newMessage;
        private Component newMessageComponent;
        private boolean newMessageComponentChanged;
        private boolean cancelled;

        public LovechatMessageEditEvent(Player player, int messageId, String oldMessage, String newMessage) {
            this(player, messageId, oldMessage, Component.text(oldMessage), newMessage, Component.text(newMessage));
        }

        public LovechatMessageEditEvent(Player player, int messageId, String oldMessage, Component oldMessageComponent, String newMessage, Component newMessageComponent) {
            // Сейчас вызывается только с асинхронного потока чата, но фиксируем поток
            // динамически, чтобы событие не упало при вызове из команды.
            super(!Bukkit.isPrimaryThread());
            this.player = player;
            this.messageId = messageId;
            this.oldMessage = oldMessage;
            this.oldMessageComponent = oldMessageComponent;
            this.newMessage = newMessage;
            this.newMessageComponent = newMessageComponent;
        }

        public Player getPlayer() { return player; }
        public int getMessageId() { return messageId; }
        public String getOldMessage() { return oldMessage; }
        public Component getOldMessageComponent() { return oldMessageComponent; }

        public String getNewMessage() { return newMessage; }
        public void setNewMessage(String newMessage) {
            this.newMessage = newMessage;
            this.newMessageComponent = Component.text(newMessage);
            this.newMessageComponentChanged = false;
        }
        public Component getNewMessageComponent() { return newMessageComponent; }
        public void setNewMessageComponent(Component newMessageComponent) {
            this.newMessageComponent = newMessageComponent;
            this.newMessage = PlainTextComponentSerializer.plainText().serialize(newMessageComponent);
            this.newMessageComponentChanged = true;
        }
        public boolean hasNewMessageComponentChanged() { return newMessageComponentChanged; }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
        @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @SuppressWarnings("unused")
    public static class LovechatMessageEvent extends Event implements Cancellable {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private String message;
        private Component messageComponent;
        private String channel;
        private boolean messageComponentChanged;
        private boolean cancelled;

        public LovechatMessageEvent(Player player, String message, String channel) {
            this(player, message, Component.text(message), channel);
        }

        public LovechatMessageEvent(Player player, String message, Component messageComponent, String channel) {
            // См. LovechatMentionEvent: ChatListener перепрыгивает на планировщик игрока
            // перед обработкой, поэтому событие вызывается с главного потока.
            super(!Bukkit.isPrimaryThread());
            this.player = player;
            this.message = message;
            this.messageComponent = messageComponent;
            this.channel = channel;
        }

        public Player getPlayer() { return player; }
        public String getMessage() { return message; }
        public void setMessage(String message) {
            this.message = message;
            this.messageComponent = Component.text(message);
            this.messageComponentChanged = false;
        }
        public Component getMessageComponent() { return messageComponent; }
        public void setMessageComponent(Component messageComponent) {
            this.messageComponent = messageComponent;
            this.message = PlainTextComponentSerializer.plainText().serialize(messageComponent);
            this.messageComponentChanged = true;
        }
        public boolean hasMessageComponentChanged() { return messageComponentChanged; }
        public String getChannel() { return channel; }
        public void setChannel(String channel) { this.channel = channel; }

        @Override public boolean isCancelled() { return cancelled; }
        @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }
        @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }

    @SuppressWarnings("unused")
    public static class CMISkinChangeEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final Player player;
        private final String skinName;
        private final String texture;

        public CMISkinChangeEvent(Player player, String skinName, String texture) {
            this.player = player;
            this.skinName = skinName;
            this.texture = texture;
        }

        @NotNull public Player getPlayer() { return player; }
        @NotNull public String getSkinName() { return skinName != null ? skinName : "default"; }
        @NotNull public String getTexture() { return texture != null ? texture : ""; }

        @NotNull @Override public HandlerList getHandlers() { return HANDLERS; }
        public static HandlerList getHandlerList() { return HANDLERS; }
    }
}
