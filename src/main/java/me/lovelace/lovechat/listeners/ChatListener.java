package me.lovelace.lovechat.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.clip.placeholderapi.PlaceholderAPI;
import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.managers.ChatBubbleManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
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
import me.lovelace.lovechat.api.LovechatAPI.LovechatMentionEvent;
import org.jetbrains.annotations.NotNull;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import me.lovelace.lovechat.depends.HeadComponentUtil;

@SuppressWarnings({"unused", "UnsubstitutedExpression", "HttpUrlsUsage", "DuplicatedCode"})
public class ChatListener implements Listener {
    private final Lovechat plugin;
    private final MiniMessage miniMessage;
    private final ChatBubbleManager chatBubbleManager;

    private final Pattern mentionPattern = Pattern.compile("(?i)@([a-zA-Z0-9_А-Яа-яЁё]+)");
    private final Pattern linkPattern = Pattern.compile("(?i)\\b(?:https?://)?(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[^\\s<>\"]*)?\\b");

    private final Map<UUID, Long> staffCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Long> everyoneCooldowns = new ConcurrentHashMap<>();

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
        everyoneCooldowns.remove(uuid);
        plugin.setTagsDisabled(uuid, false);
        if (plugin.isSpy(uuid)) plugin.toggleSpy(uuid);
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

        int radius = resolveRadius(channels, activeChannel);

        String perm = channels != null ? channels.getString(activeChannel + ".permission", "NONE") : "NONE";
        if (!perm.equalsIgnoreCase("NONE") && !player.hasPermission(perm) && (activeChannel == null || !plugin.getCustomChannels().containsKey(activeChannel))) {
            plugin.sendMessage(player, "no-permission-channel");
            return;
        }

        notifyStaffIfKeywordMatched(player, message);

        Component contentComponent;
        Set<Player> mentionedPlayers = new HashSet<>();
        Location senderLoc = player.getLocation();
        int finalRadius = radius;

        if (useApiComponent) {
            contentComponent = apiEvent.getMessageComponent();
        } else if (isJsonChatAllowed(player, message)) {
            contentComponent = parseJsonContent(message);
        } else {
            message = applyColorEscaping(player, message);
            message = applyLinkFormatting(player, message);
            message = applyMentions(player, message, mentionedPlayers, senderLoc, finalRadius);
            contentComponent = miniMessage.deserialize(message);
        }

        int messageId = plugin.getNextMessageId();
        UUID senderUuid = player.getUniqueId();
        plugin.getMessageDataCache().put(messageId, new Lovechat.MessageData(senderUuid, activeChannel, apiEvent.getMessage(), player.hasPermission("lovechat.admin")));
        plugin.setLastMessageId(player.getUniqueId(), messageId);

        plugin.getDatabaseManager().incrementMessageCount(player.getUniqueId());
        plugin.getDatabaseManager().logMessage(messageId, player.getUniqueId(), message);

        String format = resolveFormat(player, channels, activeChannel);
        String hoverText = resolveHoverText(player);
        String clickCmd = resolveClickCommand(player);

        Component headComponent = buildHeadComponent(player);
        Component playerComponent = buildPlayerComponent(player, headComponent, hoverText, clickCmd);

        String formatOthers = plugin.getConfig().getString("mentions.format-others", "<green>%player%</green>");
        String formatTarget = plugin.getConfig().getString("mentions.format-target", "<yellow>%player%</yellow>");

        String msgOthers = message;
        for (Player m : mentionedPlayers) msgOthers = msgOthers.replace("<mention_" + m.getName() + ">", formatOthers.replace("%player%", m.getName()));

        Component baseComponent = miniMessage.deserialize(format,
                Placeholder.component("player", playerComponent),
                Placeholder.component("message", mentionedPlayers.isEmpty() ? contentComponent : miniMessage.deserialize(msgOthers))
        );

        Map<UUID, Component> mentionedComps = buildMentionedComponents(mentionedPlayers, message, format, playerComponent, formatTarget, formatOthers);

        boolean senderIsAdmin = player.hasPermission("lovechat.admin") && plugin.getConfig().getBoolean("ignore.admins-bypass", true);
        boolean isAdminChannel = "admin".equals(activeChannel);

        String buttons = buildButtons(messageId);

        broadcastToRecipients(player, senderUuid, baseComponent, buttons, mentionedComps, isAdminChannel, senderIsAdmin, finalRadius, senderLoc, messageId);

        Bukkit.getConsoleSender().sendMessage(baseComponent);

        final String finalMessage = message;
        final String finalChannel = activeChannel;
        Bukkit.getRegionScheduler().execute(plugin, senderLoc, () -> {
            if (finalChannel != null) {
                chatBubbleManager.showBubble(player, finalMessage, finalChannel);
            }
        });
    }

    private boolean handleEditSession(AsyncChatEvent event, Player player, String rawMessage, Component incomingMessageComponent) {
        Lovechat.EditSession session = plugin.getEditSession(player.getUniqueId());
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

    private int resolveRadius(ConfigurationSection channels, String activeChannel) {
        int radius = channels != null ? channels.getInt(activeChannel + ".radius", 200) : 200;
        if (activeChannel != null && plugin.getCustomChannels().containsKey(activeChannel)) {
            radius = plugin.getCustomChannels().get(activeChannel);
        }
        return radius;
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
                            Placeholder.parsed("player", player.getName()),
                            Placeholder.parsed("message", message)
                    );

                    String soundConfig1 = plugin.getConfig().getString("staff-notifications.sound");
                    String soundNameStr = soundConfig1 != null ? soundConfig1 : "block.note_block.pling";
                    Key soundKey1 = Key.key(soundNameStr.contains(":") ? soundNameStr : "minecraft:" + soundNameStr.toLowerCase(Locale.ROOT));

                    for (Player p : Bukkit.getOnlinePlayers()) {
                        if (p.hasPermission(plugin.getConfig().getString("staff-notifications.permission-receive", "lovechat.staff.receive"))) {
                            p.sendMessage(staffAlert);
                            try {
                                p.playSound(Sound.sound(soundKey1, Sound.Source.MASTER, 1f, 1f));
                            } catch (Exception ignored) {}
                        }
                    }
                }
                break;
            }
        }
    }

    private boolean isJsonChatAllowed(Player player, String message) {
        return plugin.getConfig().getBoolean("chat.chat-json", true)
                && player.hasPermission(plugin.getConfig().getString("chat.permission", "lovechat.json"))
                && message.trim().startsWith("{") && message.trim().endsWith("}");
    }

    private Component parseJsonContent(String message) {
        try {
            return GsonComponentSerializer.gson().deserialize(message);
        } catch (Exception e) {
            return miniMessage.deserialize("<red>[Ошибка JSON форматирования]</red> " + miniMessage.escapeTags(message));
        }
    }

    private String applyColorEscaping(Player player, String message) {
        if (!player.hasPermission("lovechat.color")) {
            return miniMessage.escapeTags(message);
        }
        return message;
    }

    private String applyLinkFormatting(Player player, String message) {
        if (!(plugin.getConfig().getBoolean("links.enabled", true) && player.hasPermission(plugin.getConfig().getString("links.permission", "lovechat.links")))) {
            return message;
        }

        Matcher linkMatcher = linkPattern.matcher(message);
        StringBuilder linkSb = new StringBuilder();
        String linkFormat = plugin.getConfig().getString("links.format", "<hover:show_text:'<gray>Нажмите для перехода:<br><white>%link%</white>'><click:open_url:'%url%'><aqua><b>[Ссылка]</b></aqua></click></hover>");

        while (linkMatcher.find()) {
            String originalLink = linkMatcher.group();
            String url = originalLink;

            if (!url.toLowerCase(Locale.ROOT).startsWith("http://") && !url.toLowerCase(Locale.ROOT).startsWith("https://")) {
                url = "https://" + url;
            }

            String replacement = linkFormat.replace("%url%", url).replace("%link%", originalLink);
            linkMatcher.appendReplacement(linkSb, Matcher.quoteReplacement(replacement));
        }
        linkMatcher.appendTail(linkSb);
        return linkSb.toString();
    }

    private String applyMentions(Player player, String message, Set<Player> mentionedPlayers, Location senderLoc, int finalRadius) {
        if (!plugin.getConfig().getBoolean("mentions.enabled", true)) return message;

        Matcher m = mentionPattern.matcher(message);
        StringBuilder sb = new StringBuilder();

        List<String> everyoneAliases = plugin.getConfig().getStringList("mentions.everyone-aliases");
        if (everyoneAliases.isEmpty()) {
            everyoneAliases = Arrays.asList("everyone", "all", "here", "все", "всем");
        }

        while (m.find()) {
            String name = m.group(1);

            boolean isEveryoneMention = false;
            for (String alias : everyoneAliases) {
                if (name.equalsIgnoreCase(alias)) {
                    isEveryoneMention = true;
                    break;
                }
            }

            if (isEveryoneMention) {
                handleEveryoneMention(player, name, m, sb, mentionedPlayers, senderLoc, finalRadius);
                continue;
            }

            handlePlayerMention(player, name, m, sb, mentionedPlayers, senderLoc, finalRadius);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private void handleEveryoneMention(Player player, String name, Matcher m, StringBuilder sb, Set<Player> mentionedPlayers, Location senderLoc, int finalRadius) {
        if (player.hasPermission("lovechat.mention.everyone")) {

            long lastTime = everyoneCooldowns.getOrDefault(player.getUniqueId(), 0L);
            int cdSeconds = plugin.getConfig().getInt("mentions.everyone-cooldown", 300);
            long timeLeft = (lastTime + (cdSeconds * 1000L)) - System.currentTimeMillis();

            if (timeLeft > 0 && !player.hasPermission("lovechat.mention.everyone.bypass")) {
                plugin.sendMessage(player, "mention-everyone-cooldown", "{time}", String.valueOf(timeLeft / 1000));
                m.appendReplacement(sb, "@" + name);
                return;
            }

            everyoneCooldowns.put(player.getUniqueId(), System.currentTimeMillis());

            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.equals(player)) continue;
                if (plugin.isWorldDisabled(p.getWorld().getName())) continue;
                if (plugin.hasTagsDisabled(p.getUniqueId())) continue;
                if (finalRadius != -1 && (!p.getWorld().equals(senderLoc.getWorld()) || p.getLocation().distanceSquared(senderLoc) > finalRadius * finalRadius)) continue;

                mentionedPlayers.add(p);
            }

            String formatEveryone = plugin.getConfig().getString("mentions.format-everyone", "<gradient:#FF5555:#FFAA00><b>@%alias%</b></gradient>");
            m.appendReplacement(sb, formatEveryone.replace("%alias%", name));
        } else {
            m.appendReplacement(sb, "@" + name);
        }
    }

    private void handlePlayerMention(Player player, String name, Matcher m, StringBuilder sb, Set<Player> mentionedPlayers, Location senderLoc, int finalRadius) {
        Player target = Bukkit.getPlayerExact(name);

        if (target == null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(name.toLowerCase(Locale.ROOT))) {
                    target = p;
                    break;
                }
            }
        }

        if (target != null) {
            if (finalRadius != -1 && (!target.getWorld().equals(senderLoc.getWorld()) || target.getLocation().distanceSquared(senderLoc) > finalRadius * finalRadius)) {
                target = null;
            }
            else if (plugin.hasTagsDisabled(target.getUniqueId())) {
                target = null;
            }
            else if (plugin.isWorldDisabled(target.getWorld().getName())) {
                target = null;
            }
        }

        if (target != null && player.hasPermission(plugin.getConfig().getString("mentions.permission-mention", "lovechat.mention"))) {
            mentionedPlayers.add(target);
            Bukkit.getPluginManager().callEvent(new LovechatMentionEvent(player, target));
            m.appendReplacement(sb, "<mention_" + target.getName() + ">");
        } else {
            m.appendReplacement(sb, "@" + name);
        }
    }

    private String resolveFormat(Player player, ConfigurationSection channels, String activeChannel) {
        String format;
        if (channels != null && activeChannel != null) {
            format = channels.getString(activeChannel + ".format", plugin.getConfig().getString("chat.default-format", "<player>: <message>"));
        } else {
            format = plugin.getConfig().getString("chat.default-format", "<player>: <message>");
        }

        // Убираем <delete_edit_buttons> из формата, они будут добавлены индивидуально для каждого игрока
        format = format.replace("<delete_edit_buttons>", "");

        // Замена плейсхолдера имени игрока для hover/click команд
        format = format.replace("%player_name%", player.getName());

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) format = PlaceholderAPI.setPlaceholders(player, format);
        format = stripPlayerHoverClick(format);
        return format;
    }

    private String resolveHoverText(Player player) {
        String hoverText = plugin.getConfig().getString("colors.hover.player-hover", "<gray>Инфо</gray>").replace("{player}", player.getName());
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) hoverText = PlaceholderAPI.setPlaceholders(player, hoverText);
        hoverText = hoverText.replace("\n", "<br>");
        return hoverText;
    }

    private String resolveClickCommand(Player player) {
        List<String> clickCmds = plugin.getConfig().getStringList("colors.hover.click-command");
        return clickCmds.isEmpty() ? "/msg {player} " : clickCmds.getFirst().replace("{player}", player.getName());
    }

    private Component buildHeadComponent(Player player) {
        // Создаём компонент игрока: голова + имя с hover/click
        // Пытаемся получить кастомный скин из CMI
        me.lovelace.lovechat.depends.CMISkinUtil.SkinProperty skinProp =
                me.lovelace.lovechat.depends.CMISkinUtil.getSkinProperty(player);
        String skinSignature = skinProp != null ? skinProp.signature() : null;
        Component headComponent = HeadComponentUtil.createHeadComponent(
                player.getUniqueId(),
                player.getName(),
                skinProp != null ? skinProp.value() : null,
                skinSignature
        );

        if (headComponent == null && skinProp != null && skinProp.value() != null && !skinProp.value().isEmpty()) {
            String propertiesJson;
            if (skinProp.signature() != null && !skinProp.signature().isEmpty()) {
                propertiesJson = "\"properties\":[{\"name\":\"textures\",\"value\":\"" + skinProp.value()
                        + "\",\"signature\":\"" + skinProp.signature() + "\"}]";
            } else {
                propertiesJson = "\"properties\":[{\"name\":\"textures\",\"value\":\"" + skinProp.value() + "\"}]";
            }
            String fakeUuid = String.valueOf(HeadComponentUtil.makeSkinUuid(player.getName()));
            String fakeName = HeadComponentUtil.makeSkinName(player.getName());
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + fakeUuid
                    + "\",\"name\":\"" + fakeName + "\"," + propertiesJson + "}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        } else if (headComponent == null) {
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + player.getUniqueId()
                    + "\",\"name\":\"" + player.getName() + "\"}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        }
        return headComponent;
    }

    private Component buildPlayerComponent(Player player, Component headComponent, String hoverText, String clickCmd) {
        // Имя игрока (используем просто имя, а не displayName)
        Component playerNameDisplay = Component.text(player.getName()).font(Key.key("minecraft:default"));

        // Полный компонент: голова + пробел + имя с hover/click
        return Component.empty()
                .append(headComponent)
                .append(Component.text(" "))
                .append(playerNameDisplay)
                .hoverEvent(HoverEvent.showText(MiniMessage.miniMessage().deserialize(hoverText)))
                .clickEvent(ClickEvent.runCommand(clickCmd));
    }

    private Map<UUID, Component> buildMentionedComponents(Set<Player> mentionedPlayers, String message, String format, Component playerComponent, String formatTarget, String formatOthers) {
        Map<UUID, Component> mentionedComps = new HashMap<>();

        String soundConfig2 = plugin.getConfig().getString("mentions.sound");
        String soundNameStr2 = soundConfig2 != null ? soundConfig2 : "entity.experience_orb.pickup";
        Key soundKey2 = Key.key(soundNameStr2.contains(":") ? soundNameStr2 : "minecraft:" + soundNameStr2.toLowerCase(Locale.ROOT));

        for (Player target : mentionedPlayers) {
            String msgTarget = message;
            for (Player t2 : mentionedPlayers) {
                if (t2.equals(target)) {
                    msgTarget = msgTarget.replace("<mention_" + t2.getName() + ">", formatTarget.replace("%player%", t2.getName()));
                } else {
                    msgTarget = msgTarget.replace("<mention_" + t2.getName() + ">", formatOthers.replace("%player%", t2.getName()));
                }
            }

            mentionedComps.put(target.getUniqueId(), miniMessage.deserialize(format,
                    Placeholder.component("player", playerComponent),
                    Placeholder.component("message", miniMessage.deserialize(msgTarget))
            ));

            if (!soundNameStr2.equalsIgnoreCase("NONE")) {
                try {
                    target.playSound(Sound.sound(soundKey2, net.kyori.adventure.sound.Sound.Source.MASTER, 1f, 1f));
                } catch (Exception ignored) {}
            }
            if (plugin.getConfig().getBoolean("mentions.actionbar.enabled", true)) {
                target.sendActionBar(miniMessage.deserialize(plugin.getConfig().getString("mentions.actionbar.text", "<gold>Вас упомянули в чате!</gold>")));
            }
        }
        return mentionedComps;
    }

    private String buildButtons(int messageId) {
        // Генерация кнопок удаления/редактирования
        String editPrefix = "";
        if (plugin.getConfig().getBoolean("messageedit.enabled", true)) {
            editPrefix = " <hover:show_text:'<yellow>Изменить'><click:run_command:'/medit " + messageId + "'>" + plugin.getConfig().getString("messageedit.prefix", "<yellow>[✎]</yellow>") + "</click></hover>";
        }
        String delPrefix = "";
        if (plugin.getConfig().getBoolean("messagedelete.enabled", true)) {
            delPrefix = "<hover:show_text:'<red>Удалить'><click:run_command:'/md " + messageId + "'>" + plugin.getConfig().getString("messagedelete.prefix", "<dark_gray>[<red>x</red>]</dark_gray>") + "</click></hover>";
        }
        // Порядок: сначала редактировать, потом удалить
        return editPrefix + delPrefix;
    }

    private void broadcastToRecipients(Player sender, UUID senderUuid, Component baseComponent, String buttons, Map<UUID, Component> mentionedComps, boolean isAdminChannel, boolean senderIsAdmin, int finalRadius, Location senderLoc, int messageId) {
        for (Player p : Bukkit.getOnlinePlayers()) {
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

            // ИСПРАВЛЕНИЕ: Silent режим теперь блокирует ВСЁ, даже от админов
            if (plugin.isSilent(p.getUniqueId()) && !p.equals(sender)) {
                continue;
            }

            Component toSend = mentionedComps.getOrDefault(p.getUniqueId(), baseComponent);

            // Добавляем кнопки только автору сообщения или админам
            boolean isOwner = p.getUniqueId().equals(senderUuid);
            boolean isAdmin = p.hasPermission("lovechat.admin") || p.hasPermission("lovechat.moderation");

            // Кнопки добавляются ПОСЛЕ сообщения
            if (isOwner || isAdmin) {
                toSend = baseComponent.append(miniMessage.deserialize(buttons));
            }

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

    private static String stripPlayerHoverClick(String format) {
        if (format == null || format.isEmpty()) return format;
        String out = format;
        out = out.replaceAll("(?is)<hover:[^>]*>\\s*<click:[^>]*>\\s*<player>\\s*</click>\\s*</hover>", "<player>");
        out = out.replaceAll("(?is)<click:[^>]*>\\s*<hover:[^>]*>\\s*<player>\\s*</hover>\\s*</click>", "<player>");
        out = out.replaceAll("(?is)<hover:[^>]*>\\s*<player>\\s*</hover>", "<player>");
        out = out.replaceAll("(?is)<click:[^>]*>\\s*<player>\\s*</click>", "<player>");
        return out;
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
