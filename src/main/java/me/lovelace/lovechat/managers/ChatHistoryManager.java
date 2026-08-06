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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Хранит и мутирует историю чата: буфер отображённых строк на игрока, кэш данных сообщений
 * (для редактирования/удаления) и применение самих операций редактирования/удаления.
 * <p>
 * Раньше эта логика (вместе с рендером и сессиями редактирования) жила прямо в {@code Lovechat},
 * из-за чего главный класс плагина разросся в god-object. Теперь {@code Lovechat} — тонкий
 * composition root, который лишь создаёт и связывает менеджеры.
 * <p>
 * Обновление истории (item 2 code-review): раньше редактирование/удаление одной строки чата
 * полностью очищало клиентский чат (100 пустых строк) и заново пересылало ВСЮ историю игрока —
 * это моргало, сбивало прокрутку и роняло системные сообщения других плагинов, попавшие в узкое
 * временное окно {@code redrawIgnoreCache}. У протокола Minecraft (1.21.11, System Chat / Disguised
 * Chat пакеты, которыми и раньше, и сейчас пересылаются эти строки) нет пакета «заменить строку N»
 * или «удалить произвольную несигнатурную системную строку» — {@code ClientboundDeleteChatPacket}
 * работает только по подписи подписанных player chat сообщений, а Lovechat всегда отправляет
 * несигнатурные system chat сообщения. Поэтому истинный «point update» одной строки без пересылки
 * остального протоколом не поддерживается в принципе. Вместо этого теперь пересылается только
 * «хвост» истории — от изменённой/удалённой строки и до конца, без предварительной полной очистки:
 * это не трогает совсем ничего, что было до изменённой строки (включая перехваченные системные
 * сообщения других плагинов), и на порядок уменьшает и объём пересылки, и окно гонки, в которое
 * может попасть параллельное системное сообщение другого плагина.
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
            .build();

    /**
     * Точные строки (plain text), которые Lovechat сам только что переслал игроку при
     * точечном обновлении хвоста истории. {@code ProtocolLibHook} сверяется с этим кэшем,
     * чтобы не принять эхо собственной пересылки за новое системное сообщение другого плагина
     * и не задублировать его в истории. Раньше это поле существовало, но не заполнялось —
     * единственным механизмом подавления был грубый {@code redrawIgnoreCache} (булев флаг на
     * 500 мс на игрока), из-за которого любое стороннее системное сообщение, попавшее в это
     * окно, тихо терялось. Точное сопоставление по тексту вместо временного окна убирает эту
     * гонку.
     */
    private final Cache<UUID, List<String>> ignoredSentStrings = CacheBuilder.newBuilder()
            .expireAfterWrite(3, TimeUnit.SECONDS)
            .build();

    private final Component clearChatComponent = buildClearChat();

    public ChatHistoryManager(@NotNull Lovechat plugin, @NotNull MessageRenderer renderer) {
        this.plugin = plugin;
        this.renderer = renderer;
    }

    private static Component buildClearChat() {
        Component comp = Component.empty();
        for (int i = 0; i < 100; i++) {
            comp = comp.append(Component.newline());
        }
        return comp;
    }

    // ========================================================== //
    //                       ID / METADATA                        //
    // ========================================================== //

    public int getNextMessageId() { return messageIdCounter.getAndIncrement(); }
    public void setLastMessageId(@NotNull UUID uuid, int messageId) { lastMessageIds.put(uuid, messageId); }
    public @Nullable Integer getLastMessageId(@NotNull UUID uuid) { return lastMessageIds.get(uuid); }
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

        player.sendMessage(component);
    }

    public void recordSystemMessageFromPacket(@NotNull Player player, @NotNull Component component) {
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;
        int messageId = getNextMessageId();
        try {
            List<ChatLine> history = chatHistory.get(player.getUniqueId(), () -> Collections.synchronizedList(new LinkedList<>()));
            synchronized (history) {
                trimHistory(history);
                history.add(new ChatLine(messageId, component, true));
            }
        } catch (ExecutionException exception) {
            plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось добавить сообщение в историю чата", exception);
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
        player.sendMessage(clearChatComponent);
    }

    public boolean shouldIgnorePacket(@NotNull UUID uuid, @NotNull String plainText) {
        List<String> ignoredStrings = ignoredSentStrings.getIfPresent(uuid);
        return ignoredStrings != null && ignoredStrings.contains(plainText);
    }

    private void markResendIgnored(@NotNull UUID uuid, @NotNull Component component) {
        String plain = PlainTextComponentSerializer.plainText().serialize(component);
        try {
            List<String> list = ignoredSentStrings.get(uuid, CopyOnWriteArrayList::new);
            list.add(plain);
        } catch (ExecutionException ignored) {
            // Кэш недоступен — в худшем случае ProtocolLibHook один раз ошибочно задублирует
            // это эхо в историю получателя; не стоит ронять из-за этого само редактирование.
        }
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
            // здесь, у точки фактического удаления, а не только в CommandManager.
            if (!canDelete(sender, data)) {
                plugin.sendMessage(sender, "delete-not-yours");
                return;
            }
        } else if (!(sender.hasPermission("lovechat.delete.admin") || sender.isOp())) {
            // Сообщение не отслеживается (например, устарело и выпало из кэша данных) — без
            // владельца безопасно разрешить удаление только администратору.
            plugin.sendMessage(sender, "delete-not-found");
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

            List<ChatLine> tail = fullDelete
                    ? removeAndCopyTail(history, messageId)
                    : replaceAndCopyTail(history, messageId, replacement);
            if (tail == null) continue;

            p.getScheduler().run(plugin, scheduledTask -> sendTail(p, tail), null);
        }
        sender.sendMessage(MiniMessage.miniMessage().deserialize("<green>Сообщение #" + messageId + " удалено.</green>"));
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
            plugin.sendMessage(editor, "edit-not-found");
            return;
        }

        // Пункт 4 код-ревью: раньше владение сообщением проверялось только в CommandManager
        // перед стартом сессии редактирования — сам editMessageVisual ничего не проверял.
        // Дублируем проверку здесь, у точки фактического применения правки, чтобы право
        // нельзя было обойти, вызвав этот метод в обход командного слоя (API, другой плагин,
        // будущий GUI и т.д.), и чтобы права были актуальны на момент применения, а не на
        // момент запуска сессии редактирования.
        if (!EditSessionManager.canEdit(editor, data)) {
            plugin.sendMessage(editor, "edit-not-yours");
            return;
        }

        boolean useIncomingComponent = !Component.text(newText).equals(newTextComponent);
        LovechatAPI.LovechatMessageEditEvent editEvent = new LovechatAPI.LovechatMessageEditEvent(
                editor, messageId, data.rawText(), Component.text(data.rawText()), newText, newTextComponent
        );
        Bukkit.getPluginManager().callEvent(editEvent);

        if (editEvent.isCancelled()) {
            plugin.sendMessage(editor, "edit-cancelled");
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

        plugin.sendMessage(editor, "edit-success");
    }

    private void applyEditToHistory(int messageId, MessageRenderer.RenderedMessage rendered) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isWorldDisabled(p.getWorld().getName())) continue;

            List<ChatLine> history = chatHistory.getIfPresent(p.getUniqueId());
            if (history == null) continue;

            Component newComponent = rendered.forViewer(p);
            List<ChatLine> tail = replaceAndCopyTail(history, messageId, newComponent);
            if (tail == null) continue;

            p.getScheduler().run(plugin, scheduledTask -> sendTail(p, tail), null);
        }
    }

    // ========================================================== //
    //           ТОЧЕЧНОЕ ОБНОВЛЕНИЕ ХВОСТА ИСТОРИИ                //
    // ========================================================== //

    /** Заменяет строку {@code messageId} на {@code replacement} и возвращает копию хвоста (с неё и до конца). */
    private @Nullable List<ChatLine> replaceAndCopyTail(@NotNull List<ChatLine> history, int messageId, @NotNull Component replacement) {
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                ChatLine cl = history.get(i);
                if (cl.messageId() == messageId && !cl.isPluginMessage()) {
                    history.set(i, new ChatLine(messageId, replacement, false));
                    return new ArrayList<>(history.subList(i, history.size()));
                }
            }
        }
        return null;
    }

    /** Полностью убирает строку {@code messageId} из истории и возвращает копию хвоста, начиная с её позиции. */
    private @Nullable List<ChatLine> removeAndCopyTail(@NotNull List<ChatLine> history, int messageId) {
        synchronized (history) {
            for (int i = 0; i < history.size(); i++) {
                if (history.get(i).messageId() == messageId && !history.get(i).isPluginMessage()) {
                    history.remove(i);
                    int from = Math.min(i, history.size());
                    return new ArrayList<>(history.subList(from, history.size()));
                }
            }
        }
        return null;
    }

    /**
     * Пересылает хвост истории игроку БЕЗ предварительной полной очистки чата — в отличие от
     * старого поведения (100 пустых строк + вся история заново). Ограничение: клиентский чат
     * Minecraft не поддерживает удаление/замену конкретной уже показанной строки, поэтому старая
     * версия хвоста ненадолго остаётся видна выше новой — это компромисс, а не баг: полная
     * альтернатива (очистка всего буфера) достаточно дорого стоила описанными в код-ревью
     * проблемами (моргание, сброс прокрутки, потеря чужих системных сообщений).
     */
    private void sendTail(@NotNull Player p, @NotNull List<ChatLine> tail) {
        for (ChatLine line : tail) {
            markResendIgnored(p.getUniqueId(), line.component());
            p.sendMessage(line.component());
        }
    }
}
