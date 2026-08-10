package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отслеживает активные сессии редактирования сообщений (какой игрок сейчас редактирует какое
 * сообщение и каким был его старый текст).
 * <p>
 * Раньше это состояние жило прямо в {@code Lovechat} вперемешку с историей чата и рендером —
 * теперь вынесено в отдельный менеджер (см. код-ревью, пункт 3).
 */
public class EditSessionManager {

    private final Lovechat plugin;
    private final Map<UUID, EditSession> activeEditSessions = new ConcurrentHashMap<>();

    public record EditSession(int messageId, String oldText) {}

    public EditSessionManager(@NotNull Lovechat plugin) {
        this.plugin = plugin;
    }

    public void startEditSession(@NotNull Player player, int messageId, @NotNull String oldText) {
        activeEditSessions.put(player.getUniqueId(), new EditSession(messageId, oldText));

        Component promptMsg = MiniMessage.miniMessage().deserialize(plugin.getRawMsg("edit-prompt"));
        player.sendMessage(promptMsg);

        // Built via the Component API rather than string-concatenating oldText into a MiniMessage
        // string and deserializing it: oldText is the player's own previous chat message, so it's
        // untrusted input — concatenating it into markup let a message containing MiniMessage tags
        // (e.g. a stray </click><click:run_command:'...'>) inject its own click/hover events.
        Component oldMsg = Component.text("Старое сообщение: ", NamedTextColor.DARK_GRAY)
                .append(Component.text(oldText, NamedTextColor.WHITE)
                        .clickEvent(ClickEvent.suggestCommand(oldText))
                        .hoverEvent(HoverEvent.showText(Component.text("Нажмите, чтобы скопировать в строку ввода"))));
        player.sendMessage(oldMsg);
    }

    public @Nullable EditSession getSession(@NotNull UUID uuid) { return activeEditSessions.get(uuid); }
    public void removeSession(@NotNull UUID uuid) { activeEditSessions.remove(uuid); }

    /**
     * Проверка прав на редактирование сообщения — используется и командой {@code /medit} перед
     * запуском сессии (для понятной обратной связи игроку), и внутри {@code ChatHistoryManager
     * #editMessageVisual} непосредственно перед применением правки (код-ревью, пункт 4):
     * так право нельзя обойти, вызвав редактирование в обход командного слоя, и оно
     * перепроверяется на актуальный момент, а не на момент запуска сессии.
     */
    public static boolean canEdit(@NotNull CommandSender sender, @NotNull ChatHistoryManager.MessageData data) {
        boolean isAdmin = sender.hasPermission("lovechat.edit.admin") || sender.isOp();
        boolean isOwn = sender instanceof Player p && data.owner().equals(p.getUniqueId());
        return isAdmin || (isOwn && sender.hasPermission("lovechat.edit"));
    }
}
