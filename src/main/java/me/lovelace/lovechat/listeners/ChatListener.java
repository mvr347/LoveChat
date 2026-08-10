package me.lovelace.lovechat.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.managers.ChatBubbleManager;
import me.lovelace.lovechat.managers.ChatHistoryManager;
import me.lovelace.lovechat.managers.EditSessionManager;
import me.lovelace.lovechat.render.MessageRenderer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.key.Key;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import me.lovelace.lovechat.api.LovechatAPI.LovechatMessageEvent;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Слушатель нового входящего чата.
 * <p>
 * Построение отображаемого компонента (голова, hover/click, упоминания, ссылки, кнопки,
 * формат) больше не дублируется здесь — вся эта логика вынесена в {@link MessageRenderer}
 * и используется как этим слушателем (путь отправки), так и {@code ChatHistoryManager}
 * (путь редактирования). Здесь остаётся только то, что специфично именно для приёма нового
 * сообщения: маршрутизация по каналу, кулдауны, лимиты и рассылка получателям.
 */
@SuppressWarnings({"unused", "UnsubstitutedExpression", "HttpUrlsUsage", "DuplicatedCode"})
public class ChatListener implements Listener {
    private final Lovechat plugin;
    /** Ключи звуков, о поломке которых уже сообщили — чтобы не писать в лог на каждое сообщение. */
    private final java.util.Set<String> reportedBadSounds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final MiniMessage miniMessage;
    private final ChatBubbleManager chatBubbleManager;

    private final Map<UUID, Long> staffCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> chatCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Long>> channelCooldowns = new ConcurrentHashMap<>();
    private final Map<String, Map<UUID, Deque<Long>>> channelMessageTimestamps = new ConcurrentHashMap<>();

    /**
     * Проигрывает звук, не роняя обработку сообщения из-за кривого ключа в конфиге.
     * Раньше исключение глушилось молча, и «почему нет звука» выяснялось только опытом;
     * теперь про каждый плохой ключ сообщается один раз за запуск, без спама на каждое сообщение.
     */
    private void playSoundSafely(@NotNull net.kyori.adventure.audience.Audience audience,
                                 @NotNull net.kyori.adventure.key.Key soundKey,
                                 @NotNull Sound sound) {
        try {
            audience.playSound(sound);
        } catch (Exception exception) {
            if (reportedBadSounds.add(soundKey.asString())) {
                plugin.getLogger().log(java.util.logging.Level.WARNING,
                    "Не удалось проиграть звук " + soundKey.asString() + " — проверьте ключ в конфиге", exception);
            }
        }
    }

    public ChatListener(@NotNull Lovechat plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        this.chatBubbleManager = plugin.getChatBubbleManager();
    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        plugin.getDatabaseManager().getDefaultChannel(uuid).thenAccept(channel -> {
            if (channel != null) plugin.setDefaultChannel(uuid, channel);
        });
        plugin.getDatabaseManager().getIgnores(uuid).thenAccept(ignores -> plugin.loadIgnores(uuid, ignores));
        plugin.getDatabaseManager().getTagsDisabled(uuid).thenAccept(disabled -> {
            if (disabled) plugin.setTagsDisabled(uuid, true);
        });

    }

    @EventHandler
    @SuppressWarnings("unused")
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        plugin.removeEditSession(uuid);
        plugin.removeDefaultChannel(uuid);
        staffCooldowns.remove(uuid);
        plugin.getMessageRenderer().clearEveryoneCooldown(uuid);
        chatCooldowns.remove(uuid);
        plugin.setTagsDisabled(uuid, false);
        if (plugin.isSpy(uuid)) plugin.toggleSpy(uuid);
        if (plugin.isSilent(uuid)) plugin.toggleSilent(uuid);
        plugin.clearIgnoredMemory(uuid);
        for (Map<UUID, Long> map : channelCooldowns.values()) map.remove(uuid);
        for (Map<UUID, Deque<Long>> map : channelMessageTimestamps.values()) map.remove(uuid);
        chatBubbleManager.removeBubble(uuid);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    @SuppressWarnings("unused")
    public void onModernChat(@NotNull AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;
        Component incomingMessageComponent = event.message();
        String rawMessage = PlainTextComponentSerializer.plainText().serialize(incomingMessageComponent);
        boolean useIncomingComponent = !isPlainTextComponent(incomingMessageComponent, rawMessage);

        if (handleEditSession(event, player, rawMessage, incomingMessageComponent)) return;

        event.setCancelled(true);

        // AsyncChatEvent runs off the main/region thread. Everything below touches
        // Bukkit APIs (online players, locations, sending messages, shared caches),
        // so hop to the player's own scheduler thread before doing any of that work.
        final String finalRawMessage = rawMessage;
        final Component finalIncomingComponent = incomingMessageComponent;
        final boolean finalUseIncomingComponent = useIncomingComponent;
        player.getScheduler().run(plugin, scheduledTask ->
                processChatMessage(player, finalRawMessage, finalIncomingComponent, finalUseIncomingComponent), null);
    }

    private void processChatMessage(@NotNull Player player, String rawMessage, Component incomingMessageComponent, boolean useIncomingComponent) {
        ConfigurationSection channels = plugin.getConfig().getConfigurationSection("colors.channels");
        String activeChannel = plugin.getDefaultChannel(player.getUniqueId());

        ChannelPrefixMatch prefixMatch = matchChannelPrefix(channels, rawMessage);
        if (prefixMatch != null) {
            activeChannel = prefixMatch.channel();
            rawMessage = prefixMatch.message();
            incomingMessageComponent = prefixMatch.component();
            useIncomingComponent = false;
        }

        LovechatMessageEvent apiEvent = new LovechatMessageEvent(player, rawMessage, incomingMessageComponent, activeChannel);
        Bukkit.getPluginManager().callEvent(apiEvent);
        if (apiEvent.isCancelled()) return;

        String message = apiEvent.getMessage();
        activeChannel = apiEvent.getChannel();
        boolean useApiComponent = apiEvent.hasMessageComponentChanged() || (useIncomingComponent && message.equals(rawMessage));

        String perm = channels != null ? channels.getString(activeChannel + ".permission", "NONE") : "NONE";
        if (!perm.equalsIgnoreCase("NONE") && !player.hasPermission(perm)) {
            plugin.sendMessage(player, "no-permission-channel");
            return;
        }

        int maxLength = plugin.getConfig().getInt("chat.max-length", 256);
        if (maxLength > 0 && message.length() > maxLength) {
            plugin.sendMessage(player, "message-too-long", "{max}", String.valueOf(maxLength));
            return;
        }

        if (!checkCooldowns(player, activeChannel, channels)) return;

        notifyStaffIfKeywordMatched(player, message);

        int messageId = plugin.getNextMessageId();
        UUID senderUuid = player.getUniqueId();
        ChatHistoryManager.MessageData data = new ChatHistoryManager.MessageData(senderUuid, activeChannel, message, player.hasPermission("lovechat.admin"));
        plugin.getMessageDataCache().put(messageId, data);
        plugin.setLastMessageId(player.getUniqueId(), messageId);

        plugin.getDatabaseManager().incrementMessageCount(player.getUniqueId());
        plugin.getDatabaseManager().logMessage(messageId, player.getUniqueId(), message);

        Component overrideComponent = useApiComponent ? apiEvent.getMessageComponent() : null;
        MessageRenderer.RenderAuthor author = MessageRenderer.RenderAuthor.of(player);
        MessageRenderer.RenderedMessage rendered = plugin.getMessageRenderer().render(messageId, data, author, overrideComponent);

        boolean senderIsAdmin = player.hasPermission("lovechat.admin") && plugin.getConfig().getBoolean("ignore.admins-bypass", true);
        boolean isAdminChannel = "admin".equals(activeChannel);
        int radius = plugin.getMessageRenderer().resolveRadius(channels, activeChannel);
        Location senderLoc = player.getLocation();

        broadcastToRecipients(player, senderUuid, rendered, isAdminChannel, senderIsAdmin, radius, senderLoc, messageId);

        Bukkit.getConsoleSender().sendMessage(rendered.baseComponent());

        final String finalMessage = message;
        final String finalChannel = activeChannel;
        Bukkit.getRegionScheduler().execute(plugin, senderLoc, () -> {
            if (finalChannel != null) {
                chatBubbleManager.showBubble(player, finalMessage, finalChannel);
            }
        });
    }

    private boolean handleEditSession(AsyncChatEvent event, Player player, String rawMessage, Component incomingMessageComponent) {
        EditSessionManager.EditSession session = plugin.getEditSession(player.getUniqueId());
        if (session == null) return false;
        event.setCancelled(true);
        if (rawMessage.equalsIgnoreCase("cancel")) {
            plugin.removeEditSession(player.getUniqueId());
            plugin.sendMessage(player, "edit-cancelled-click");
            return true;
        }
        plugin.editMessageVisual(session.messageId(), rawMessage, incomingMessageComponent, player);
        plugin.removeEditSession(player.getUniqueId());
        return true;
    }

    private record ChannelPrefixMatch(String channel, String message, Component component) {}

    private ChannelPrefixMatch matchChannelPrefix(ConfigurationSection channels, String rawMessage) {
        if (channels == null) return null;
        for (String key : channels.getKeys(false)) {
            String prefix = channels.getString(key + ".prefix", "");
            if (!prefix.isEmpty() && rawMessage.startsWith(prefix)) {
                String newMessage = rawMessage.substring(prefix.length()).trim();
                return new ChannelPrefixMatch(key, newMessage, Component.text(newMessage));
            }
        }
        return null;
    }

    private boolean checkCooldowns(Player player, String activeChannel, ConfigurationSection channels) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean bypass = player.hasPermission("lovechat.cooldown.bypass");
        String cooldownMessage = plugin.getConfig().getString("chat.cooldown-message", "<red>Подождите <yellow>{time}</yellow> сек. перед следующим сообщением!</red>");

        int globalCooldown = plugin.getConfig().getInt("chat.cooldown", 0);
        if (globalCooldown > 0 && !bypass) {
            long last = chatCooldowns.getOrDefault(uuid, 0L);
            long remaining = (last + globalCooldown * 1000L) - now;
            if (remaining > 0) {
                player.sendMessage(miniMessage.deserialize(cooldownMessage.replace("{time}", String.valueOf(remaining / 1000 + 1))));
                return false;
            }
        }

        if (activeChannel != null && channels != null) {
            int channelCooldown = channels.getInt(activeChannel + ".cooldown", 0);
            if (channelCooldown > 0 && !bypass) {
                Map<UUID, Long> map = channelCooldowns.computeIfAbsent(activeChannel, k -> new ConcurrentHashMap<>());
                long last = map.getOrDefault(uuid, 0L);
                long remaining = (last + channelCooldown * 1000L) - now;
                if (remaining > 0) {
                    player.sendMessage(miniMessage.deserialize(cooldownMessage.replace("{time}", String.valueOf(remaining / 1000 + 1))));
                    return false;
                }
                map.put(uuid, now);
            }

            int maxPerMinute = channels.getInt(activeChannel + ".max-per-minute", -1);
            if (maxPerMinute >= 0 && !bypass) {
                Map<UUID, Deque<Long>> map = channelMessageTimestamps.computeIfAbsent(activeChannel, k -> new ConcurrentHashMap<>());
                Deque<Long> timestamps = map.computeIfAbsent(uuid, k -> new ArrayDeque<>());
                synchronized (timestamps) {
                    while (!timestamps.isEmpty() && now - timestamps.peekFirst() > 60_000L) timestamps.pollFirst();
                    if (timestamps.size() >= maxPerMinute) {
                        plugin.sendMessage(player, "channel-max-per-minute", "{channel}", activeChannel);
                        return false;
                    }
                    timestamps.addLast(now);
                }
            }
        }

        chatCooldowns.put(uuid, now);
        return true;
    }

    private void notifyStaffIfKeywordMatched(Player player, String message) {
        if (!plugin.getConfig().getBoolean("staff-notifications.enabled", true)) return;
        List<String> keywords = plugin.getConfig().getStringList("staff-notifications.keywords");
        String lowerMessage = message.toLowerCase(Locale.ROOT);

        for (String kw : keywords) {
            if (lowerMessage.contains(kw.toLowerCase(Locale.ROOT))) {
                long lastTime = staffCooldowns.getOrDefault(player.getUniqueId(), 0L);
                int cd = plugin.getConfig().getInt("staff-notifications.cooldown", 60);
                if (System.currentTimeMillis() - lastTime > cd * 1000L) {
                    staffCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

                    String staffFormat = plugin.getConfig().getString("staff-notifications.format", "<red>[!]</red> <player>: <message>");
                    Component staffAlert = miniMessage.deserialize(staffFormat,
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.parsed("player", player.getName()),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.parsed("message", message)
                    );

                    String soundConfig1 = plugin.getConfig().getString("staff-notifications.sound");
                    String soundNameStr = soundConfig1 != null ? soundConfig1 : "block.note_block.pling";
                    Key soundKey1 = Key.key(soundNameStr.contains(":") ? soundNameStr : "minecraft:" + soundNameStr.toLowerCase(Locale.ROOT));

                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission(plugin.getConfig().getString("staff-notifications.permission-receive", "lovechat.staff.receive"))) {
                            p.sendMessage(staffAlert);
                            playSoundSafely(p, soundKey1, Sound.sound(soundKey1, Sound.Source.MASTER, 1f, 1f));
                        }
                    }
                }
                break;
            }
        }
    }

    private void broadcastToRecipients(Player sender, UUID senderUuid, MessageRenderer.RenderedMessage rendered, boolean isAdminChannel, boolean senderIsAdmin, int finalRadius, Location senderLoc, int messageId) {
        // Global chat (no radius) has to consider every online player anyway, so there's no
        // gain from a nearby-players lookup there - only local/radius chat benefits, since on a
        // busy server the radius-recipient set is normally far smaller than "everyone online".
        // Spy-mode viewers still need the message regardless of distance, so they're unioned in
        // separately rather than falling out of a plain nearby-players scan.
        Collection<? extends Player> candidates;
        if (finalRadius == -1) {
            candidates = Bukkit.getOnlinePlayers();
        } else {
            Set<Player> nearby = new HashSet<>(senderLoc.getNearbyPlayers(finalRadius));
            for (UUID spyUuid : plugin.getSpyPlayers()) {
                Player spyPlayer = Bukkit.getPlayer(spyUuid);
                if (spyPlayer != null) nearby.add(spyPlayer);
            }
            candidates = nearby;
        }

        for (Player p : candidates) {
            if (plugin.isWorldDisabled(p.getWorld().getName())) {
                continue;
            }

            // Игроки без пермиссиона не должны видеть сообщения из админ-канала
            if (isAdminChannel && !p.hasPermission("lovechat.admin")) {
                continue;
            }

            // Ignore учитывает обход админов
            if (plugin.isIgnoring(p.getUniqueId(), senderUuid) && !senderIsAdmin) {
                continue;
            }

            // Silent режим блокирует ВСЁ, даже от админов
            if (plugin.isSilent(p.getUniqueId()) && !p.equals(sender)) {
                continue;
            }

            Component toSend = rendered.forViewer(p);

            if (finalRadius != -1 && (!p.getWorld().equals(senderLoc.getWorld()) || p.getLocation().distanceSquared(senderLoc) > finalRadius * finalRadius)) {
                if (plugin.isSpy(p.getUniqueId())) {
                    toSend = miniMessage.deserialize("<dark_gray>[SPY] </dark_gray>").append(toSend);
                } else {
                    continue;
                }
            }

            plugin.addChatLineAndSend(p, messageId, toSend);
        }
    }

    private static boolean isPlainTextComponent(@NotNull Component component, @NotNull String plainText) {
        return Component.text(plainText).equals(component);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("unused")
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;
        String cmd = event.getMessage().toLowerCase();

        if (cmd.equals("/medit cancel") || cmd.equals("/cancel")) return;

        if (plugin.getEditSession(player.getUniqueId()) != null) {
            plugin.removeEditSession(player.getUniqueId());
            plugin.sendMessage(player, "edit-cancelled-command");
        }
    }
}
