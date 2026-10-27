package me.lovelace.lovechat.depends;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Reads the chat packets the server sends to each player, so that system messages of other plugins
 * land in the chat history and survive a redraw. Replaces the former ProtocolLib listener: a plain
 * outbound handler is put into the player's Netty pipeline right before the server's own
 * {@code packet_handler} (the same anchor LoveAuth uses), where packets are still unencoded objects.
 * <p>
 * The handler only reads: every packet is passed on untouched. It runs on a Netty thread, so it never
 * touches Bukkit; the "world is disabled" state is a volatile flag refreshed from the main side.
 * <p>
 * Everything about the server internals is resolved reflectively once at startup. If any of it is
 * missing (a Minecraft update renamed something), the hook stays inactive with a single warning and
 * LoveChat keeps working in a degraded mode: the history then holds only LoveChat's own lines.
 */
public final class NettyChatHook {

    private static final String HANDLER_NAME = "lovechat_chat";
    private static final String ANCHOR = "packet_handler";
    private static final String BLANK_BLOCK = "\n".repeat(20);

    private final Lovechat plugin;
    private final Map<UUID, Injected> injected = new ConcurrentHashMap<>();
    private final Reflect reflect;
    private volatile boolean failureLogged;

    public NettyChatHook(@NotNull Lovechat plugin) {
        this.plugin = plugin;
        this.reflect = Reflect.resolve(plugin);
    }

    /** False when the server internals could not be resolved; nothing is recorded then. */
    public boolean isActive() {
        return reflect != null;
    }

    /** Adds the handler to the player's connection (no-op when already there or when the hook is inactive). */
    public void inject(@NotNull Player player) {
        if (reflect == null) return;
        try {
            Channel channel = reflect.channelOf(player);
            if (channel == null) return;
            UUID uuid = player.getUniqueId();
            boolean disabled = plugin.isWorldDisabled(player.getWorld().getName());
            Injected existing = injected.get(uuid);
            if (existing != null && existing.channel == channel && channel.pipeline().get(HANDLER_NAME) != null) {
                existing.worldDisabled = disabled;
                return;
            }
            // After a reload the old handler (from the previous plugin classloader) may still sit there.
            channel.eventLoop().execute(() -> {
                ChannelPipeline pipeline = channel.pipeline();
                if (pipeline.get(HANDLER_NAME) != null) pipeline.remove(HANDLER_NAME);
                if (pipeline.get(ANCHOR) == null) return;
                Injected state = new Injected(channel, disabled);
                injected.put(uuid, state);
                pipeline.addBefore(ANCHOR, HANDLER_NAME, new Handler(uuid, state));
            });
        } catch (Throwable t) {
            warnOnce("Could not hook the chat packets of " + player.getName(), t);
        }
    }

    public void uninject(@NotNull UUID uuid) {
        Injected state = injected.remove(uuid);
        if (state == null) return;
        Channel channel = state.channel;
        channel.eventLoop().execute(() -> {
            try {
                if (channel.pipeline().get(HANDLER_NAME) != null) channel.pipeline().remove(HANDLER_NAME);
            } catch (Throwable ignored) {
                // The channel is closing; the pipeline goes away with it.
            }
        });
    }

    public void uninjectAll() {
        for (UUID uuid : injected.keySet().toArray(new UUID[0])) uninject(uuid);
    }

    /** Re-reads whether the player's current world has LoveChat disabled. */
    public void refreshWorld(@NotNull Player player) {
        Injected state = injected.get(player.getUniqueId());
        if (state != null) state.worldDisabled = plugin.isWorldDisabled(player.getWorld().getName());
    }

    private void warnOnce(String message, Throwable t) {
        if (failureLogged) return;
        failureLogged = true;
        plugin.getLogger().log(Level.WARNING, message + "; chat history will hold only LoveChat's own lines", t);
    }

    private static final class Injected {
        final Channel channel;
        volatile boolean worldDisabled;

        Injected(Channel channel, boolean worldDisabled) {
            this.channel = channel;
            this.worldDisabled = worldDisabled;
        }
    }

    private final class Handler extends ChannelOutboundHandlerAdapter {
        private final UUID uuid;
        private final Injected state;

        Handler(UUID uuid, Injected state) {
            this.uuid = uuid;
            this.state = state;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            try {
                if (!state.worldDisabled) inspect(msg);
            } catch (Throwable t) {
                warnOnce("Failed to read an outgoing chat packet", t);
            }
            ctx.write(msg, promise);
        }

        private void inspect(Object packet) throws ReflectiveOperationException {
            if (reflect.bundleClass != null && reflect.bundleClass.isInstance(packet)) {
                for (Object sub : (Iterable<?>) reflect.bundlePackets.invoke(packet)) inspect(sub);
                return;
            }
            Object content;
            if (reflect.systemChatClass.isInstance(packet)) {
                // Action bar texts must not become chat lines.
                if ((boolean) reflect.systemChatOverlay.invoke(packet)) return;
                content = reflect.systemChatContent.invoke(packet);
            } else if (reflect.disguisedChatClass != null && reflect.disguisedChatClass.isInstance(packet)) {
                content = reflect.disguisedChatMessage.invoke(packet);
            } else {
                return;
            }
            if (content == null) return;
            Component component = (Component) reflect.asAdventure.invoke(null, content);
            String plain = PlainTextComponentSerializer.plainText().serialize(component);

            // Our own sends are registered beforehand, see ChatHistoryManager#sendTracked.
            if (plugin.consumeOwnEcho(uuid, plain)) return;
            // Safety net for our own redraw (it opens with a block of blank lines) and for chat clears of
            // other plugins: such a packet in the history would be replayed, and grow, with every redraw.
            if (plain.isBlank() || plain.startsWith(BLANK_BLOCK)) return;
            // Safety net for an echo whose text did not round-trip exactly: our edit/delete buttons.
            if (plain.contains("✎") || plain.contains("[x]") || plain.contains("(изм.)")) return;

            plugin.recordSystemMessageFromPacket(uuid, component);
        }
    }

    /** Server internals, resolved once. Mojang-mapped names, as Paper 26.x ships them. */
    private static final class Reflect {
        final Method getHandle;
        final @Nullable Class<?> bundleClass;
        final @Nullable Method bundlePackets;
        final Class<?> systemChatClass;
        final Method systemChatContent;
        final Method systemChatOverlay;
        final @Nullable Class<?> disguisedChatClass;
        final @Nullable Method disguisedChatMessage;
        final Method asAdventure;
        // Resolved lazily from the first real player, because the field path starts from a live object.
        private volatile Field[] channelPath;

        private Reflect(Method getHandle, @Nullable Class<?> bundleClass, @Nullable Method bundlePackets,
                        Class<?> systemChatClass, Method systemChatContent, Method systemChatOverlay,
                        @Nullable Class<?> disguisedChatClass, @Nullable Method disguisedChatMessage,
                        Method asAdventure) {
            this.getHandle = getHandle;
            this.bundleClass = bundleClass;
            this.bundlePackets = bundlePackets;
            this.systemChatClass = systemChatClass;
            this.systemChatContent = systemChatContent;
            this.systemChatOverlay = systemChatOverlay;
            this.disguisedChatClass = disguisedChatClass;
            this.disguisedChatMessage = disguisedChatMessage;
            this.asAdventure = asAdventure;
        }

        static @Nullable Reflect resolve(Lovechat plugin) {
            try {
                String craftPackage = plugin.getServer().getClass().getPackage().getName();
                Class<?> craftPlayer = Class.forName(craftPackage + ".entity.CraftPlayer");
                Class<?> component = Class.forName("net.minecraft.network.chat.Component");
                String protocol = "net.minecraft.network.protocol.game.";
                Class<?> systemChat = Class.forName(protocol + "ClientboundSystemChatPacket");
                Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");

                // Optional parts: a plugin message arrives as a system chat packet, bundles and
                // disguised chat only widen the net. If they are gone, the rest still works.
                Class<?> bundle = null;
                Method bundlePackets = null;
                try {
                    bundle = Class.forName("net.minecraft.network.protocol.BundlePacket");
                    bundlePackets = bundle.getMethod("subPackets");
                } catch (ReflectiveOperationException missing) {
                    bundle = null;
                    plugin.getLogger().warning("Bundle packets are not read by the chat hook: " + missing);
                }
                Class<?> disguisedChat = null;
                Method disguisedMessage = null;
                try {
                    disguisedChat = Class.forName(protocol + "ClientboundDisguisedChatPacket");
                    disguisedMessage = disguisedChat.getMethod("message");
                } catch (ReflectiveOperationException missing) {
                    disguisedChat = null;
                    plugin.getLogger().warning("Disguised chat packets are not read by the chat hook: " + missing);
                }
                return new Reflect(
                        craftPlayer.getMethod("getHandle"),
                        bundle,
                        bundlePackets,
                        systemChat,
                        systemChat.getMethod("content"),
                        systemChat.getMethod("overlay"),
                        disguisedChat,
                        disguisedMessage,
                        paperAdventure.getMethod("asAdventure", component));
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING,
                        "The server internals LoveChat reads chat packets from were not found (Minecraft update?); "
                                + "chat history will hold only LoveChat's own lines, update LoveChat", t);
                return null;
            }
        }

        @Nullable Channel channelOf(Player player) throws ReflectiveOperationException {
            Object handle = getHandle.invoke(player);
            Field[] path = channelPath;
            if (path == null) {
                // ServerPlayer.connection (game listener) -> its Connection -> Connection.channel
                Field listener = findFieldByType(handle.getClass(), "ServerGamePacketListenerImpl");
                Field connection = findFieldByType(listener.getType(), "Connection");
                Field channel = findFieldByType(connection.getType(), "Channel");
                path = new Field[]{listener, connection, channel};
                channelPath = path;
            }
            Object value = handle;
            for (Field field : path) {
                value = field.get(value);
                if (value == null) return null;
            }
            return (Channel) value;
        }

        private static Field findFieldByType(Class<?> owner, String typeSimpleName) throws NoSuchFieldException {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (f.getType().getSimpleName().equals(typeSimpleName)
                            || (typeSimpleName.equals("Channel") && f.getType() == Channel.class)) {
                        f.setAccessible(true);
                        return f;
                    }
                }
            }
            throw new NoSuchFieldException(typeSimpleName + " in " + owner.getName());
        }
    }
}
