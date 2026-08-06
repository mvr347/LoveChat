package me.lovelace.lovechat.render;

import me.clip.placeholderapi.PlaceholderAPI;
import me.lovelace.lovechat.Lovechat;
import me.lovelace.lovechat.depends.CMISkinUtil;
import me.lovelace.lovechat.depends.HeadComponentUtil;
import me.lovelace.lovechat.managers.ChatHistoryManager.MessageData;
import me.lovelace.lovechat.utils.LegacyCodeSanitizer;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Единая точка построения отображаемого сообщения чата.
 * <p>
 * Раньше построение компонента (голова игрока, hover/click, упоминания, ссылки, кнопки
 * редактирования/удаления, итоговый формат) было продублировано почти построчно между
 * {@code ChatListener} (отправка нового сообщения) и {@code Lovechat#editMessageVisual}
 * (редактирование). Из-за этого путь редактирования не проходил через обработку упоминаний
 * и ссылок и резолвил PlaceholderAPI по {@link OfflinePlayer} вместо онлайн-игрока.
 * <p>
 * Теперь оба пути — отправка и редактирование — идут через единственный метод
 * {@link #render}, поэтому получают гарантированно одинаковое форматирование.
 */
public class MessageRenderer {

    private final Lovechat plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Pattern mentionPattern = Pattern.compile("(?i)@([a-zA-Z0-9_А-Яа-яЁё]+)");
    private final Pattern linkPattern = Pattern.compile("(?i)\\b(?:https?://)?(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,}(?:/[^\\s<>\"]*)?\\b");

    /** Кулдаун @everyone — общий для отправки и редактирования, т.к. обе ветки идут через {@link #render}. */
    private final Map<UUID, Long> everyoneCooldowns = new ConcurrentHashMap<>();

    public MessageRenderer(@NotNull Lovechat plugin) {
        this.plugin = plugin;
    }

    /** Сбрасывает кулдаун @everyone игрока, например при выходе с сервера. */
    public void clearEveryoneCooldown(@NotNull UUID uuid) {
        everyoneCooldowns.remove(uuid);
    }

    /**
     * Контекст «автора» отображаемого сообщения.
     * <p>
     * {@code identity} — чей это профиль (голова/имя/hover) — это ВСЕГДА владелец сообщения,
     * независимо от того, кто вызвал рендер (отправитель или редактор).
     * <p>
     * {@code online} — онлайн-игрок, от чьего имени резолвятся права (цвета/ссылки/упоминания)
     * и PlaceholderAPI. При отправке нового сообщения это всегда сам отправитель. При
     * редактировании — это владелец сообщения, если он сейчас в сети, иначе {@code null}
     * (тогда пайплайн упоминаний/ссылок аккуратно отключается, а PAPI резолвится по offline-профилю,
     * как единственная доступная деградация — раньше это было единственным путём безусловно,
     * даже когда владелец был онлайн, из-за чего и появлялся баг с PAPI/OfflinePlayer).
     */
    public record RenderAuthor(@NotNull OfflinePlayer identity, @Nullable Player online) {

        public static RenderAuthor of(@NotNull Player player) {
            return new RenderAuthor(player, player);
        }

        public UUID uuid() {
            return identity.getUniqueId();
        }

        public String name() {
            String name = identity.getName();
            return name != null ? name : "Unknown";
        }

        public boolean hasPermission(String permission) {
            return online != null && online.hasPermission(permission);
        }

        /** Цель для {@code PlaceholderAPI#setPlaceholders} — Player, если игрок в сети, иначе offline-профиль. */
        public OfflinePlayer papiTarget() {
            return online != null ? online : identity;
        }

        public @Nullable Location location() {
            return online != null ? online.getLocation() : null;
        }
    }

    /**
     * Результат рендера: базовый компонент, персональные варианты для упомянутых игроков
     * (другой текст упоминания для цели/остальных) и MiniMessage-кнопки редактирования/удаления.
     * {@link #forViewer(Player)} применяет per-viewer логику (какой вариант компонента показать,
     * добавлять ли кнопки) — эта логика идентична и для рассылки нового сообщения, и для
     * точечного обновления строки в истории при редактировании (см. {@code ChatHistoryManager}).
     */
    public record RenderedMessage(UUID ownerUuid, Component baseComponent,
                                   Map<UUID, Component> mentionedComponents,
                                   Set<Player> mentionedPlayers, String buttons) {

        public Component forViewer(@NotNull Player viewer) {
            Component component = mentionedComponents.getOrDefault(viewer.getUniqueId(), baseComponent);

            boolean isOwner = viewer.getUniqueId().equals(ownerUuid);
            boolean isAdmin = viewer.hasPermission("lovechat.admin") || viewer.hasPermission("lovechat.moderation");

            if ((isOwner || isAdmin) && !buttons.isEmpty()) {
                component = component.append(MiniMessage.miniMessage().deserialize(buttons));
            }
            return component;
        }
    }

    /**
     * Строит отображаемое сообщение чата для {@code messageId}.
     *
     * @param messageId        ID сообщения (нужен для click-команд кнопок /medit и /md)
     * @param data             данные сообщения (владелец, канал, сырой текст, счётчик редактирований)
     * @param author           контекст автора для рендера (см. {@link RenderAuthor})
     * @param overrideContent  если не {@code null} — используется как есть в качестве содержимого
     *                         сообщения (например, JSON-чат или компонент, подставленный слушателем
     *                         API-события), минуя пайплайн упоминаний/ссылок/цветов — так же, как это
     *                         всегда работало при отправке нового сообщения.
     */
    public RenderedMessage render(int messageId, @NotNull MessageData data, @NotNull RenderAuthor author,
                                   @Nullable Component overrideContent) {
        ConfigurationSection channels = plugin.getConfig().getConfigurationSection("colors.channels");
        String activeChannel = data.channel();
        int radius = resolveRadius(channels, activeChannel);

        Set<Player> mentionedPlayers = new HashSet<>();
        Component contentComponent;
        String messageForFormat = data.rawText();

        if (overrideContent != null) {
            contentComponent = overrideContent;
        } else if (isJsonChatAllowed(author, messageForFormat)) {
            contentComponent = parseJsonContent(messageForFormat);
        } else {
            String processed = applyColorEscaping(author, messageForFormat);
            processed = applyLinkFormatting(author, processed);
            processed = applyMentions(author, processed, mentionedPlayers, radius);
            messageForFormat = processed;
            contentComponent = miniMessage.deserialize(processed);
        }

        String format = resolveFormat(author, channels, activeChannel);
        String hoverText = resolveHoverText(author);
        String clickCmd = resolveClickCommand(author);

        Component headComponent = buildHeadComponent(author);
        Component playerComponent = buildPlayerComponent(author, headComponent, hoverText, clickCmd);

        String formatOthers = plugin.getConfig().getString("mentions.format-others", "<green>%player%</green>");
        String formatTarget = plugin.getConfig().getString("mentions.format-target", "<yellow><b>%player%</b></yellow>");

        String msgOthers = messageForFormat;
        for (Player m : mentionedPlayers) {
            msgOthers = msgOthers.replace("<mention_" + m.getName() + ">", formatOthers.replace("%player%", m.getName()));
        }

        String editedMark = data.editCount() > 0
                ? plugin.getConfig().getString("messageedit.mark", " <dark_gray><i>(изм.)</i></dark_gray>")
                : "";

        Component baseComponent = miniMessage.deserialize(format + editedMark,
                Placeholder.component("player", playerComponent),
                Placeholder.component("message", mentionedPlayers.isEmpty() ? contentComponent : miniMessage.deserialize(msgOthers))
        );

        Map<UUID, Component> mentionedComps = buildMentionedComponents(mentionedPlayers, messageForFormat, format + editedMark, playerComponent, formatTarget, formatOthers);

        String buttons = buildButtons(messageId);

        return new RenderedMessage(author.uuid(), baseComponent, mentionedComps, mentionedPlayers, buttons);
    }

    // ========================================================== //
    //                   ПОСТРОЕНИЕ КНОПОК                         //
    // ========================================================== //

    /**
     * Единственное место, где строится MiniMessage-строка кнопок [✎][x].
     * Раньше эта конкатенация была продублирована в {@code ChatListener} и в
     * {@code Lovechat#editMessageVisual} по отдельности.
     */
    public String buildButtons(int messageId) {
        String editPrefix = "";
        if (plugin.getConfig().getBoolean("messageedit.enabled", true)) {
            editPrefix = " <hover:show_text:'<yellow>Изменить'><click:run_command:'/medit " + messageId + "'>"
                    + plugin.getConfig().getString("messageedit.prefix", "<yellow>[✎]</yellow>") + "</click></hover>";
        }
        String delPrefix = "";
        if (plugin.getConfig().getBoolean("messagedelete.enabled", true)) {
            delPrefix = "<hover:show_text:'<red>Удалить'><click:run_command:'/md " + messageId + "'>"
                    + plugin.getConfig().getString("messagedelete.prefix", "<dark_gray>[<red>x</red>]</dark_gray>") + "</click></hover>";
        }
        // Порядок: сначала редактировать, потом удалить
        return editPrefix + delPrefix;
    }

    // ========================================================== //
    //              ПАЙПЛАЙН СОДЕРЖИМОГО СООБЩЕНИЯ                //
    // ========================================================== //

    /** Публичный, т.к. также нужен {@code ChatListener} для фильтрации получателей по радиусу при рассылке. */
    public int resolveRadius(ConfigurationSection channels, String activeChannel) {
        int radius = channels != null ? channels.getInt(activeChannel + ".radius", 200) : 200;
        if (activeChannel != null && plugin.getCustomChannels().containsKey(activeChannel)) {
            radius = plugin.getCustomChannels().get(activeChannel);
        }
        return radius;
    }

    private boolean isJsonChatAllowed(RenderAuthor author, String message) {
        return plugin.getConfig().getBoolean("chat.chat-json", true)
                && author.hasPermission(plugin.getConfig().getString("chat.permission", "lovechat.json"))
                && message.trim().startsWith("{") && message.trim().endsWith("}");
    }

    private Component parseJsonContent(String message) {
        try {
            return GsonComponentSerializer.gson().deserialize(message);
        } catch (Exception e) {
            return miniMessage.deserialize("<red>[Ошибка JSON форматирования]</red> " + miniMessage.escapeTags(message));
        }
    }

    /**
     * Экранирует MiniMessage-теги для игроков без права на цвета.
     * Если владелец сообщения сейчас офлайн (редко — например, админ правит старое сообщение
     * отключившегося игрока), права проверить негде, поэтому текст безопасно экранируется.
     */
    private String applyColorEscaping(RenderAuthor author, String message) {
        if (!author.hasPermission("lovechat.color")) {
            return miniMessage.escapeTags(message);
        }
        return message;
    }

    private String applyLinkFormatting(RenderAuthor author, String message) {
        if (!(plugin.getConfig().getBoolean("links.enabled", true) && author.hasPermission(plugin.getConfig().getString("links.permission", "lovechat.links")))) {
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

    /**
     * Обрабатывает упоминания (@player, @everyone). Требует онлайн-контекста автора (для
     * радиуса/локации и живых прав) — если владелец сообщения офлайн, упоминания оставляются
     * как обычный текст без преобразования, чтобы не обращаться к отсутствующей локации.
     */
    private String applyMentions(RenderAuthor author, String message, Set<Player> mentionedPlayers, int finalRadius) {
        if (!plugin.getConfig().getBoolean("mentions.enabled", true) || author.online() == null) {
            return message;
        }
        Player onlineAuthor = author.online();
        Location senderLoc = author.location();

        Matcher m = mentionPattern.matcher(message);
        StringBuilder sb = new StringBuilder();

        List<String> everyoneAliases = plugin.getConfig().getStringList("mentions.everyone-aliases");
        if (everyoneAliases.isEmpty()) {
            everyoneAliases = List.of("everyone", "all", "here", "все", "всем");
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
                handleEveryoneMention(onlineAuthor, name, m, sb, mentionedPlayers, senderLoc, finalRadius);
                continue;
            }

            handlePlayerMention(onlineAuthor, name, m, sb, mentionedPlayers, senderLoc, finalRadius);
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
            } else if (plugin.hasTagsDisabled(target.getUniqueId())) {
                target = null;
            } else if (plugin.isWorldDisabled(target.getWorld().getName())) {
                target = null;
            }
        }

        if (target != null && player.hasPermission(plugin.getConfig().getString("mentions.permission-mention", "lovechat.mention"))) {
            mentionedPlayers.add(target);
            Bukkit.getPluginManager().callEvent(new me.lovelace.lovechat.api.LovechatAPI.LovechatMentionEvent(player, target));
            m.appendReplacement(sb, "<mention_" + target.getName() + ">");
        } else {
            m.appendReplacement(sb, "@" + name);
        }
    }

    private String resolveFormat(RenderAuthor author, ConfigurationSection channels, String activeChannel) {
        String format;
        if (channels != null && activeChannel != null) {
            format = channels.getString(activeChannel + ".format", plugin.getConfig().getString("chat.default-format", "<player>: <message>"));
        } else {
            format = plugin.getConfig().getString("chat.default-format", "<player>: <message>");
        }

        // Убираем <delete_edit_buttons> из формата, они будут добавлены индивидуально для каждого игрока
        format = format.replace("<delete_edit_buttons>", "");

        // Замена плейсхолдера имени игрока для hover/click команд
        format = format.replace("%player_name%", author.name());

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            format = LegacyCodeSanitizer.toMiniMessage(PlaceholderAPI.setPlaceholders(author.papiTarget(), format));
        }
        format = stripPlayerHoverClick(format);
        return format;
    }

    private String resolveHoverText(RenderAuthor author) {
        String hoverText = plugin.getConfig().getString("colors.hover.player-hover", "<gray>Инфо</gray>").replace("{player}", author.name());
        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            hoverText = LegacyCodeSanitizer.toMiniMessage(PlaceholderAPI.setPlaceholders(author.papiTarget(), hoverText));
        }
        return hoverText.replace("\n", "<br>");
    }

    private String resolveClickCommand(RenderAuthor author) {
        List<String> clickCmds = plugin.getConfig().getStringList("colors.hover.click-command");
        return clickCmds.isEmpty() ? "/msg {player} " : clickCmds.getFirst().replace("{player}", author.name());
    }

    private Component buildHeadComponent(RenderAuthor author) {
        // Создаём компонент игрока: голова + имя с hover/click.
        // Пытаемся получить кастомный скин из CMI — через Player, если владелец онлайн,
        // иначе через UUID/имя (единственный доступный путь для офлайн-профиля).
        CMISkinUtil.SkinProperty skinProp = author.online() != null
                ? CMISkinUtil.getSkinProperty(author.online())
                : CMISkinUtil.getSkinProperty(author.uuid(), author.name());

        String skinSignature = skinProp != null ? skinProp.signature() : null;
        Component headComponent = HeadComponentUtil.createHeadComponent(
                author.uuid(),
                author.name(),
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
            String fakeUuid = String.valueOf(HeadComponentUtil.makeSkinUuid(author.name()));
            String fakeName = HeadComponentUtil.makeSkinName(author.name());
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + fakeUuid
                    + "\",\"name\":\"" + fakeName + "\"," + propertiesJson + "}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        } else if (headComponent == null) {
            String headJson = "{\"text\":\"\",\"extra\":[{\"text\":\"\",\"type\":\"minecraft:player\",\"id\":\""
                    + author.uuid()
                    + "\",\"name\":\"" + author.name() + "\"}]}";
            headComponent = GsonComponentSerializer.gson().deserialize(headJson);
        }
        return headComponent;
    }

    private Component buildPlayerComponent(RenderAuthor author, Component headComponent, String hoverText, String clickCmd) {
        Component playerNameDisplay = Component.text(author.name()).font(Key.key("minecraft:default"));

        return Component.empty()
                .append(headComponent)
                .append(Component.text(" "))
                .append(playerNameDisplay)
                .hoverEvent(HoverEvent.showText(miniMessage.deserialize(hoverText)))
                .clickEvent(ClickEvent.runCommand(clickCmd));
    }

    private Map<UUID, Component> buildMentionedComponents(Set<Player> mentionedPlayers, String message, String format, Component playerComponent, String formatTarget, String formatOthers) {
        Map<UUID, Component> mentionedComps = new HashMap<>();
        if (mentionedPlayers.isEmpty()) return mentionedComps;

        String soundConfig = plugin.getConfig().getString("mentions.sound");
        String soundNameStr = soundConfig != null ? soundConfig : "entity.experience_orb.pickup";
        Key soundKey = Key.key(soundNameStr.contains(":") ? soundNameStr : "minecraft:" + soundNameStr.toLowerCase(Locale.ROOT));

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

            if (!soundNameStr.equalsIgnoreCase("NONE")) {
                try {
                    target.playSound(Sound.sound(soundKey, Sound.Source.MASTER, 1f, 1f));
                } catch (Exception ignored) {
                    // Некорректный ключ звука в конфиге — не роняем обработку сообщения из-за этого.
                }
            }
            if (plugin.getConfig().getBoolean("mentions.actionbar.enabled", true)) {
                target.sendActionBar(miniMessage.deserialize(plugin.getConfig().getString("mentions.actionbar.text", "<gold>Вас упомянули в чате!</gold>")));
            }
        }
        return mentionedComps;
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
}
