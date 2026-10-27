package me.lovelace.lovechat.managers;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.api.LovechatAPI;
import me.lovelace.lovechat.render.MessageRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Хранит и мутирует историю чата: буфер отображённых строк на игрока, кэш данных сообщений
 * (для редактирования/удаления) и применение самих операций редактирования/удаления.
 * <p>
 * У протокола Minecraft нет пакета «заменить строку N», а {@code ClientboundDeleteChatPacket}
 * удаляет только подписанные player-chat сообщения (на offline-mode сервере подписей нет, а
 * Lovechat всегда шлёт несигнатурные system chat сообщения). Поэтому правка и удаление — это
 * перерисовка чата: строка меняется на месте в истории игрока, после чего вся история уходит
 * ОДНИМ сообщением («пустые строки» + строки истории). Клиент применяет такое сообщение целиком
 * за один кадр, промежуточного пустого чата не существует — поэтому нет моргания, а старой копии
 * строки на экране не остаётся. Сообщения других плагинов лежат в той же истории (их записывает
 * {@code NettyChatHook}) и остаются на своих местах.
 */
public class ChatHistoryManager {

    private final Lovechat plugin;
    private final MessageRenderer renderer;

    public record ChatLine(int messageId, Component component, boolean isPluginMessage) {}

    public record MessageData(UUID owner, String channel, String rawText, boolean isStaff, long createdAt, int editCount) {
        public MessageData(UUID owner, String channel, String rawText, boolean isStaff) {
            this(owner, channel, rawText, isStaff, System.currentTimeMillis(), 0);
        }
    }

    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private final Map<UUID, Integer> lastMessageIds = new ConcurrentHashMap<>();

    private final Cache<UUID, List<ChatLine>> chatHistory = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .maximumSize(2000)
            .build();
    private final Cache<Integer, MessageData> messageDataCache = CacheBuilder.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(10000)
            .build();

    /**
     * Строки, которые Lovechat сам только что отправил игроку (новое сообщение или перерисовка).
     * Перед отправкой текст регистрируется, а {@code NettyChatHook}, увидев такой же исходящий
     * пакет, снимает запись и НЕ записывает пакет в историю как «чужое» сообщение. Так эхо
     * собственной отправки отличается от системного сообщения другого плагина точным совпадением
     * текста, а не временным окном, в которое попадало и чужое сообщение.
     */
    private final OwnEchoes ownEchoes = new OwnEchoes();

    /** Столько переводов строки вытесняют из клиентского буфера (100 строк) всё, что было до перерисовки. */
    private static final int CLEAR_LINES = 100;

    private final Component clearChatComponent = buildClearChat();

    public ChatHistoryManager(@NotNull Lovechat plugin, @NotNull MessageRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
    }

    private static Component buildClearChat() {
        Component comp = Component.empty();
        for (int i = 0; i < CLEAR_LINES; i++) {
            comp = comp.append(Component.newline());
        }
        return comp;
    }

    // ========================================================== //
    //                       ID / METADATA                        //
    // ========================================================== //

    public int getNextMessageId() { return messageIdCounter.getAndIncrement(); }
    /** Code-review fix: seeds the counter from the DB's highest persisted id at startup so it
     *  doesn't always restart at 1 and collide with not-yet-purged rows from before a restart
     *  (which silently failed their INSERT on the messages table's PRIMARY KEY). Must be called
     *  once during plugin startup, before any message is sent. */
    public void seedMessageIdCounter(int nextId) { messageIdCounter.set(nextId); }
    public void setLastMessageId(@NotNull UUID uuid, int messageId) { lastMessageIds.put(uuid, messageId); }
    public @Nullable Integer getLastMessageId(@NotNull UUID uuid) { return lastMessageIds.get(uuid); }
    /** Drops the in-memory "last message id" cache for a player on quit. Unlike default-channel,
     *  tags-disabled and ignore state, this value is never persisted to the database and never
     *  reloaded on join - it exists only to serve the %lovechat_message_id_last% placeholder for
     *  the current session. Without this, lastMessageIds grew by one entry for every unique
     *  player who ever sent a single chat message, forever, for the life of the server. */
    public void removeLastMessageId(@NotNull UUID uuid) { lastMessageIds.remove(uuid); }
    public @NotNull Cache<Integer, MessageData> getMessageDataCache() { return messageDataCache; }

    public void cleanUp() {
        messageDataCache.cleanUp();
        chatHistory.cleanUp();
    }

    // ========================================================== //
    //                    ХРАНЕНИЕ ИСТОРИИ                         //
    // ========================================================== //

    public void addChatLineAndSend(@NotNull Player player, int messageId, @NotNull Component component) {
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;
        try {
            List<ChatLine> history = chatHistory.get(player.getUniqueId(), () -> Collections.synchronizedList(new LinkedList<>()));
            synchronized (history) {
                trimHistory(history);
                history.add(new ChatLine(messageId, component, false));
            }
        } catch (ExecutionException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось добавить сообщение в историю чата", exception);
        }

        sendTracked(player, component);
    }

    /** Called by {@code NettyChatHook} (Netty thread) for a system message of another plugin. */
    public void recordSystemMessageFromPacket(@NotNull UUID uuid, @NotNull Component component) {
        int messageId = getNextMessageId();
        try {
            List<ChatLine> history = chatHistory.get(uuid, () -> Collections.synchronizedList(new LinkedList<>()));
            synchronized (history) {
                trimHistory(history);
                history.add(new ChatLine(messageId, component, true));
            }
        } catch (ExecutionException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось добавить сообщение в историю чата", exception);
        }
    }

    /**
     * Public API for other plugins (e.g. LoveChatFilter): removes the packet-recorded "echo" copies
     * that follow the line of {@code messageId} in every online player's history, so a later
     * edit/delete does not resend a stale unfiltered duplicate.
     */
    public void removePluginEchoes(int messageId) {
        for (Player online : new java.util.ArrayList<>(plugin.getServer().getOnlinePlayers())) {
            List<ChatLine> history = chatHistory.getIfPresent(online.getUniqueId());
            if (history == null) continue;
            synchronized (history) {
                for (int i = 0; i < history.size(); i++) {
                    ChatLine line = history.get(i);
                    if (line.messageId() != messageId || line.isPluginMessage()) continue;
                    String expected = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                            .plainText().serialize(line.component());
                    int index = i + 1;
                    while (index < history.size()) {
                        ChatLine candidate = history.get(index);
                        if (!candidate.isPluginMessage()) break;
                        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                                .plainText().serialize(candidate.component());
                        if (!plain.equals(expected)) break;
                        history.remove(index);
                    }
                    break;
                }
            }
        }
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

    public @NotNull Component getClearChatComponent() { return clearChatComponent; }

    public void clearChatForPlayer(@NotNull Player player, @SuppressWarnings("unused") boolean keepStaff) {
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;
        sendTracked(player, clearChatComponent);
    }

    /**
     * Sends {@code component} to the player and registers it as our own, so that the packet hook
     * does not record it in the history again.
     */
    private void sendTracked(@NotNull Player player, @NotNull Component component) {
        ownEchoes.register(player.getUniqueId(), PlainTextComponentSerializer.plainText().serialize(component), System.nanoTime());
        player.sendMessage(component);
    }

    /**
     * True (and the entry is consumed) when {@code plainText} is the echo of a message we sent to
     * this player ourselves. Called from the Netty thread.
     */
    public boolean consumeOwnEcho(@NotNull UUID uuid, @NotNull String plainText) {
        return ownEchoes.consume(uuid, plainText, System.nanoTime());
    }

    /** Forgets the per-player state of a player who left. */
    public void forget(@NotNull UUID uuid) {
        ownEchoes.forget(uuid);
    }

    // ========================================================== //
    //                 ПРАВА НА УДАЛЕНИЕ / РЕДАКТИРОВАНИЕ          //
    // ========================================================== //

    /**
     * Проверка прав на удаление сообщения. Используется и командой {@code /md}, и здесь же,
     * непосредственно перед фактическим удалением — так право нельзя обойти, вызвав
     * {@link #deleteMessageVisual} в обход командного слоя.
     */
    public boolean canDelete(@NotNull CommandSender sender, @NotNull MessageData data) {
        boolean isAdmin = sender.hasPermission("lovechat.delete.admin") || sender.isOp();
        boolean isOwn = sender instanceof Player p && data.owner().equals(p.getUniqueId());
        return isAdmin || (isOwn && sender.hasPermission("lovechat.delete"));
    }

    // ========================================================== //
    //                          УДАЛЕНИЕ                           //
    // ========================================================== //

    public void deleteMessageVisual(int messageId, @NotNull CommandSender sender) {
        MessageData data = messageDataCache.getIfPresent(messageId);
        if (data != null) {
            // Как и в editMessageVisual (код-ревью, пункт 4): проверка владения дублируется
            // здесь, у точки фактического удаления, а не только в слое кнопок.
            if (!canDelete(sender, data)) {
                plugin.notice(sender, "delete-not-yours");
                return;
            }
        } else if (!(sender.hasPermission("lovechat.delete.admin") || sender.isOp())) {
            // Сообщение не отслеживается (например, устарело и выпало из кэша данных) — без
            // владельца безопасно разрешить удаление только администратору.
            plugin.notice(sender, "delete-not-found");
            return;
        }

        LovechatAPI.LovechatDeleteEvent deleteEvent = new LovechatAPI.LovechatDeleteEvent(messageId, sender);
        Bukkit.getPluginManager().callEvent(deleteEvent);

        plugin.getDatabaseManager().deleteMessage(messageId);
        messageDataCache.invalidate(messageId);

        boolean fullDelete = plugin.getConfig().getBoolean("messagedelete.full-delete", false);
        Component replacement = MiniMessage.miniMessage().deserialize(
                plugin.getConfig().getString("messagedelete.replacement-text", "<gray><i>Сообщение было удалено</i></gray>")
        );

        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isWorldDisabled(p.getWorld().getName())) continue;

            List<ChatLine> history = chatHistory.getIfPresent(p.getUniqueId());
            if (history == null) continue;

            List<ChatLine> snapshot = fullDelete
                    ? removeLine(history, messageId)
                    : replaceLine(history, messageId, replacement);
            if (snapshot == null) continue;

            p.getScheduler().run(plugin, scheduledTask -> redraw(p, history), null);
        }
        // The line disappearing (or turning into the replacement) is the confirmation for a player;
        // a console or another plugin calling this gets one explicit line.
        if (!(sender instanceof Player)) {
            sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>Сообщение #" + messageId + " удалено.</green>"));
        }
    }

    // ========================================================== //
    //                       РЕДАКТИРОВАНИЕ                        //
    // ========================================================== //

    public void editMessageVisual(int messageId, @NotNull String newText, @NotNull Player editor) {
        editMessageVisual(messageId, newText, Component.text(newText), editor);
    }

    public void editMessageVisual(int messageId, @NotNull String newText, @NotNull Component newTextComponent, @NotNull Player editor) {
        MessageData data = messageDataCache.getIfPresent(messageId);
        if (data == null) {
            plugin.notice(editor, "edit-not-found");
            return;
        }

        // Пункт 4 код-ревью: раньше владение сообщением проверялось только в CommandManager
        // перед стартом сессии редактирования — сам editMessageVisual ничего не проверял.
        // Дублируем проверку здесь, у точки фактического применения правки, чтобы право
        // нельзя было обойти, вызвав этот метод в обход слоя кнопок (API, другой плагин,
        // будущий GUI и т.д.), и чтобы права были актуальны на момент применения, а не на
        // момент открытия окна редактирования.
        if (!MessageActions.canEdit(editor, data)) {
            plugin.notice(editor, "edit-not-yours");
            return;
        }

        boolean useIncomingComponent = !Component.text(newText).equals(newTextComponent);
        LovechatAPI.LovechatMessageEditEvent editEvent = new LovechatAPI.LovechatMessageEditEvent(
                editor, messageId, data.rawText(), Component.text(data.rawText()), newText, newTextComponent
        );
        Bukkit.getPluginManager().callEvent(editEvent);

        if (editEvent.isCancelled()) {
            plugin.notice(editor, "edit-cancelled");
            return;
        }

        String finalText = editEvent.getNewMessage();
        boolean useApiComponent = editEvent.hasNewMessageComponentChanged() || (useIncomingComponent && finalText.equals(newText));
        Component overrideComponent = useApiComponent ? editEvent.getNewMessageComponent() : null;

        plugin.getDatabaseManager().updateMessage(messageId, finalText);
        MessageData updated = new MessageData(data.owner(), data.channel(), finalText, data.isStaff(), data.createdAt(), data.editCount() + 1);
        messageDataCache.put(messageId, updated);

        // Пункт 1 код-ревью: раньше здесь была почти дословная копия построения компонента из
        // ChatListener (голова, hover/click, формат, кнопки) — без обработки упоминаний/ссылок
        // и с резолвом PlaceholderAPI по OfflinePlayer вместо онлайн-игрока. Теперь оба пути —
        // и отправка нового сообщения, и редактирование — идут через один и тот же
        // MessageRenderer#render, поэтому получают одинаковое форматирование.
        Player onlineOwner = Bukkit.getPlayer(data.owner());
        OfflinePlayer identity = onlineOwner != null ? onlineOwner : Bukkit.getOfflinePlayer(data.owner());
        MessageRenderer.RenderAuthor author = new MessageRenderer.RenderAuthor(identity, onlineOwner);

        MessageRenderer.RenderedMessage rendered = renderer.render(messageId, updated, author, overrideComponent);

        applyEditToHistory(messageId, rendered);
    }

    private void applyEditToHistory(int messageId, MessageRenderer.RenderedMessage rendered) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isWorldDisabled(p.getWorld().getName())) continue;

            List<ChatLine> history = chatHistory.getIfPresent(p.getUniqueId());
            if (history == null) continue;

            Component newComponent = rendered.forViewer(p);
            List<ChatLine> snapshot = replaceLine(history, messageId, newComponent);
            if (snapshot == null) continue;

            p.getScheduler().run(plugin, scheduledTask -> redraw(p, history), null);
        }
    }

    // ========================================================== //
    //                 ЗАМЕНА СТРОКИ И ПЕРЕРИСОВКА                 //
    // ========================================================== //

    /** Заменяет строку {@code messageId} на {@code replacement} и возвращает снимок всей истории; {@code null}, если строки нет. */
    private @Nullable List<ChatLine> replaceLine(@NotNull List<ChatLine> history, int messageId, @NotNull Component replacement) {
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                ChatLine cl = history.get(i);
                if (cl.messageId() == messageId && !cl.isPluginMessage()) {
                    history.set(i, new ChatLine(messageId, replacement, false));
                    return new ArrayList<>(history);
                }
            }
        }
        return null;
    }

    /** Полностью убирает строку {@code messageId} из истории и возвращает снимок всей истории; {@code null}, если строки нет. */
    private @Nullable List<ChatLine> removeLine(@NotNull List<ChatLine> history, int messageId) {
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).messageId() == messageId && !history.get(i).isPluginMessage()) {
                    history.remove(i);
                    return new ArrayList<>(history);
                }
            }
        }
        return null;
    }

    /**
     * Собирает одно сообщение: {@link #CLEAR_LINES} пустых строк (вытесняют старый буфер клиента),
     * затем все строки истории. Вынесено отдельно ради тестов — это чистая функция.
     */
    static @NotNull Component buildRedraw(@NotNull List<ChatLine> lines) {
        net.kyori.adventure.text.TextComponent.Builder builder = Component.text();
        builder.append(Component.text("\n".repeat(CLEAR_LINES)));
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append(Component.newline());
            builder.append(lines.get(i).component());
        }
        return builder.build();
    }

    /**
     * Перерисовывает игроку чат одним пакетом. Старая версия изменённой/удалённой строки
     * исчезает, остальное (включая системные сообщения других плагинов) остаётся на местах;
     * клиент применяет сообщение целиком за один кадр, поэтому чат не моргает.
     */
    private void redraw(@NotNull Player p, @NotNull List<ChatLine> history) {
        if (!p.isOnline()) return;
        // Copied now, not when the task was queued: a line that arrived in between would otherwise
        // be cleared by the redraw and never replayed.
        List<ChatLine> lines;
        synchronized (history) {
            lines = new ArrayList<>(history);
        }
        sendTracked(p, buildRedraw(lines));
    }
}
