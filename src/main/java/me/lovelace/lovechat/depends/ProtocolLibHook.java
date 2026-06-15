package me.lovelace.lovechat.depends;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ProtocolLibHook {
    private final Lovechat lovechat;

    public ProtocolLibHook(@NotNull Lovechat plugin) {
        this.lovechat = plugin;
    }

    public void register() {
        ProtocolManager manager = ProtocolLibrary.getProtocolManager();

        manager.addPacketListener(new PacketAdapter(
                lovechat,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SYSTEM_CHAT,
                PacketType.Play.Server.DISGUISED_CHAT // Обязательно для сообщений от других плагинов
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                Player player = event.getPlayer();
                if (lovechat.isWorldDisabled(player.getWorld().getName())) {
                    return;
                }

                // Игнорируем Action Bar сообщения (чтобы они не дублировались в историю чата)
                if (event.getPacketType() == PacketType.Play.Server.SYSTEM_CHAT) {
                    var booleans = event.getPacket().getBooleans();
                    if (booleans.size() > 0 && Boolean.TRUE.equals(booleans.readSafely(0))) {
                        return;
                    }
                }

                Component component = null;
                try {
                    // 1. Попытка получить Paper Adventure Component (нативно в 1.21.11)
                    var adventureModifiers = event.getPacket().getSpecificModifier(Component.class);
                    if (adventureModifiers.size() > 0) {
                        component = adventureModifiers.readSafely(0);
                    }

                    // 2. Попытка получить ProtocolLib WrappedChatComponent (на случай другого форматирования)
                    if (component == null) {
                        var chatModifiers = event.getPacket().getChatComponents();
                        if (chatModifiers.size() > 0) {
                            WrappedChatComponent wrapped = chatModifiers.readSafely(0);
                            if (wrapped != null) {
                                component = GsonComponentSerializer.gson().deserialize(wrapped.getJson());
                            }
                        }
                    }
                } catch (Exception e) {
                    return;
                }

                if (component == null) return;

                String plainText = PlainTextComponentSerializer.plainText().serialize(component);

                // Если сообщение должно быть проигнорировано анти-спам кэшем перерисовки
                if (lovechat.shouldIgnorePacket(player.getUniqueId(), plainText)) {
                    return;
                }

                // Игнорируем технические сообщения самого плагина
                if (plainText.contains("\u270E") || plainText.contains("[x]") || plainText.contains("(изм.)")) {
                    return;
                }

                lovechat.recordSystemMessageFromPacket(player, component);
            }
        });
    }
}
