package me.lovelace.lovechat.managers;

import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The [✎] and [x] buttons of a chat line, without any command.
 * <p>
 * Both buttons carry a stateless custom click ({@code lovechat:edit} / {@code lovechat:delete}) with
 * the message id as payload, so nothing is registered per message and a button in a redrawn history
 * never expires. [✎] opens a native dialog with the old text in an input field; its "Save" button
 * is a custom click as well ({@code lovechat:edit_save}) and the text arrives in the dialog response.
 * [x] deletes at once. Every click is checked again here — permissions, time limit, edit limit —
 * because a client can send any custom click with any id; the same checks run again inside
 * {@link ChatHistoryManager#editMessageVisual} and {@link ChatHistoryManager#deleteMessageVisual}.
 * Failures are shown in the action bar, never in the chat.
 */
public final class MessageActions implements Listener {

    private static final Key EDIT = Key.key("lovechat", "edit");
    private static final Key DELETE = Key.key("lovechat", "delete");
    private static final Key SAVE = Key.key("lovechat", "edit_save");
    private static final String INPUT = "text";
    private static final int DEFAULT_MAX_LENGTH = 256;
    private static final Pattern ID = Pattern.compile("-?\\d+");

    private final Lovechat plugin;

    public MessageActions(@NotNull Lovechat plugin) {
        this.plugin = plugin;
    }

    public static @NotNull ClickEvent editClick(int messageId) {
        return ClickEvent.custom(EDIT, payload(messageId));
    }

    public static @NotNull ClickEvent deleteClick(int messageId) {
        return ClickEvent.custom(DELETE, payload(messageId));
    }

    private static BinaryTagHolder payload(int messageId) {
        return BinaryTagHolder.binaryTagHolder(Integer.toString(messageId));
    }

    /** Dialog actions accept only a compound tag as additions, unlike chat clicks. */
    private static BinaryTagHolder dialogPayload(int messageId) {
        return BinaryTagHolder.binaryTagHolder("{id:" + messageId + "}");
    }

    /** Parses the id payload (a bare number from chat, {@code {id:N}} from the dialog); {@code -1} when it is missing or not a number (a forged click). */
    static int parseId(@Nullable BinaryTagHolder tag) {
        if (tag == null) return -1;
        Matcher matcher = ID.matcher(tag.string());
        if (!matcher.find()) return -1;
        try {
            return Integer.parseInt(matcher.group());
        } catch (NumberFormatException tooBig) {
            return -1;
        }
    }

    /** Own message and the edit permission, or the admin permission. */
    public static boolean canEdit(@NotNull CommandSender sender, @NotNull ChatHistoryManager.MessageData data) {
        boolean isAdmin = sender.hasPermission("lovechat.edit.admin") || sender.isOp();
        boolean isOwn = sender instanceof Player p && data.owner().equals(p.getUniqueId());
        return isAdmin || (isOwn && sender.hasPermission("lovechat.edit"));
    }

    @EventHandler
    public void onCustomClick(@NotNull PlayerCustomClickEvent event) {
        Key id = event.getIdentifier();
        boolean edit = id.equals(EDIT);
        boolean delete = id.equals(DELETE);
        boolean save = id.equals(SAVE);
        if (!edit && !delete && !save) return;
        if (!(event.getCommonConnection() instanceof PlayerGameConnection connection)) return;

        Player player = connection.getPlayer();
        int messageId = parseId(event.getTag());
        if (messageId < 0) return;

        String text = null;
        if (save) {
            DialogResponseView view = event.getDialogResponseView();
            text = view == null ? null : view.getText(INPUT);
            if (text == null) return;
        }
        final String savedText = text;
        player.getScheduler().run(plugin, task -> {
            // The commands this replaced went through the disabled-worlds guard; so must the buttons.
            if (plugin.isWorldDisabled(player.getWorld().getName())) return;
            // Same gate chat messages pass: an unauthenticated session must not rewrite the account's lines.
            if (!plugin.isAuthenticated(player)) return;
            if (edit) openEdit(player, messageId);
            else if (delete) requestDelete(player, messageId);
            else saveEdit(player, messageId, savedText);
        }, null);
    }

    // ---------------------------------------------------------- //
    //                            EDIT                            //
    // ---------------------------------------------------------- //

    /** Message key explaining why {@code player} may not edit now, or {@code null} when allowed. */
    private @Nullable String editDenial(@NotNull Player player, @NotNull ChatHistoryManager.MessageData data) {
        if (!canEdit(player, data)) return "edit-not-yours";
        boolean isAdmin = player.hasPermission("lovechat.edit.admin") || player.isOp();
        if (isAdmin) return null;
        int editTimeLimit = plugin.getConfig().getInt("messageedit.edit-time-limit", 300);
        if (editTimeLimit > 0 && (System.currentTimeMillis() - data.createdAt()) > editTimeLimit * 1000L) {
            return "edit-time-limit-exceeded";
        }
        int maxEdits = plugin.getConfig().getInt("messageedit.max-edits", 5);
        if (maxEdits > 0 && data.editCount() >= maxEdits) return "edit-max-reached";
        return null;
    }

    private void openEdit(@NotNull Player player, int messageId) {
        ChatHistoryManager.MessageData data = plugin.getMessageDataCache().getIfPresent(messageId);
        if (data == null) {
            plugin.notice(player, "edit-not-found");
            return;
        }
        String denial = editDenial(player, data);
        if (denial != null) {
            plugin.notice(player, denial);
            return;
        }
        player.showDialog(buildEditDialog(messageId, data.rawText(), maxLength()));
    }

    private void saveEdit(@NotNull Player player, int messageId, @NotNull String rawText) {
        ChatHistoryManager.MessageData data = plugin.getMessageDataCache().getIfPresent(messageId);
        if (data == null) {
            plugin.notice(player, "edit-not-found");
            return;
        }
        String denial = editDenial(player, data);
        if (denial != null) {
            plugin.notice(player, denial);
            return;
        }
        String text = rawText.strip();
        if (text.isEmpty()) {
            plugin.notice(player, "edit-empty");
            return;
        }
        int max = maxLength();
        if (text.length() > max) {
            plugin.notice(player, "message-too-long", "{max}", String.valueOf(max));
            return;
        }
        // Nothing changed: no edit mark, no spent edit.
        if (text.equals(data.rawText())) return;
        plugin.editMessageVisual(messageId, text, player);
    }

    private int maxLength() {
        int configured = plugin.getConfig().getInt("chat.max-length", DEFAULT_MAX_LENGTH);
        return configured > 0 ? configured : DEFAULT_MAX_LENGTH;
    }

    private Dialog buildEditDialog(int messageId, @NotNull String oldText, int maxLength) {
        MiniMessage mm = MiniMessage.miniMessage();
        Component title = mm.deserialize(plugin.getRawMsg("edit-dialog-title", "<yellow>Изменить сообщение</yellow>"));
        Component inputLabel = mm.deserialize(plugin.getRawMsg("edit-dialog-input", "<gray>Текст сообщения</gray>"));
        Component saveLabel = mm.deserialize(plugin.getRawMsg("edit-dialog-save", "<green>Сохранить</green>"));
        Component cancelLabel = mm.deserialize(plugin.getRawMsg("edit-dialog-cancel", "<red>Отмена</red>"));

        String initial = oldText.length() > maxLength ? oldText.substring(0, maxLength) : oldText;
        TextDialogInput input = DialogInput.text(INPUT, inputLabel)
                .width(300)
                .initial(initial)
                .maxLength(maxLength)
                .build();
        ActionButton save = ActionButton.builder(saveLabel)
                .action(DialogAction.customClick(SAVE, dialogPayload(messageId)))
                .build();
        // No action: the button just closes the dialog.
        ActionButton cancel = ActionButton.builder(cancelLabel).build();

        return Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .pause(false)
                        .inputs(List.of(input))
                        .build())
                .type(DialogType.confirmation(save, cancel)));
    }

    // ---------------------------------------------------------- //
    //                           DELETE                           //
    // ---------------------------------------------------------- //

    private void requestDelete(@NotNull Player player, int messageId) {
        ChatHistoryManager.MessageData data = plugin.getMessageDataCache().getIfPresent(messageId);
        boolean isAdmin = player.hasPermission("lovechat.delete.admin") || player.isOp();
        if (data == null) {
            // Not tracked any more (fell out of the data cache): only an admin may remove it blindly.
            if (isAdmin) plugin.deleteMessageVisual(messageId, player);
            else plugin.notice(player, "delete-not-found");
            return;
        }
        if (!plugin.getChatHistoryManager().canDelete(player, data)) {
            plugin.notice(player, "delete-not-yours");
            return;
        }
        int deleteTimeLimit = plugin.getConfig().getInt("messagedelete.delete-time-limit", 0);
        if (!isAdmin && deleteTimeLimit > 0 && (System.currentTimeMillis() - data.createdAt()) > deleteTimeLimit * 1000L) {
            plugin.notice(player, "delete-time-limit-exceeded");
            return;
        }
        plugin.deleteMessageVisual(messageId, player);
    }
}
