package me.lovelace.lovechat.managers;

import me.lovelace.lovechat.Lovechat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер ChatBubbles - голограммы над головой игрока.
 * Использует TextDisplay сущность с посадкой на игрока (passenger system).
 * Только одно сообщение на игрока - при новом сообщении старое удаляется.
 * Использует современный Paper API Scheduler (совместим с Folia).
 */
public class ChatBubbleManager {

    private final Lovechat plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, BubbleInstance> playerBubbles = new ConcurrentHashMap<>();

    private boolean enabled;
    private int displayTime;
    private double heightOffset;
    private String format;
    private int radius;
    private int maxLength;
    private int checkRadius;
    private Set<String> allowedChannels;

    public ChatBubbleManager(@NotNull Lovechat plugin) {
        this.plugin = plugin;
        loadConfig();
    }

    public void loadConfig() {
        ConfigurationSection config = plugin.getConfig().getConfigurationSection("chatbubbles");
        if (config == null) {
            enabled = false;
            return;
        }

        enabled = config.getBoolean("enabled", true);
        displayTime = config.getInt("display-time", 60);
        heightOffset = config.getDouble("height", 1.8);
        format = config.getString("format", "<gradient:#00FF00:#00AA00><message></gradient>");
        radius = config.getInt("radius", 50);
        maxLength = config.getInt("max-length", 100);
        checkRadius = config.getInt("check-radius", 40);

        allowedChannels = new HashSet<>(config.getStringList("allowed-channels"));
        if (allowedChannels.isEmpty()) {
            allowedChannels = Set.of("local", "global");
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void showBubble(@NotNull Player player, @NotNull String rawMessage, @NotNull String channel) {
        if (!enabled) return;
        if (plugin.isWorldDisabled(player.getWorld().getName())) return;

        if (!allowedChannels.contains(channel)) {
            return;
        }

        // Проверка на наличие игроков рядом (если нет - не спавним голограмму)
        if (!hasPlayersNearby(player, checkRadius)) {
            return;
        }

        String message = rawMessage;

        if (message.length() > maxLength) {
            message = message.substring(0, maxLength - 3) + "...";
        }

        if (!player.hasPermission("lovechat.color")) {
            message = miniMessage.escapeTags(message);
        }

        // Префикс канала не добавляем, так как он уже есть в формате канала в config.yml
        // и chatbubble использует отдельный format без префикса

        Component bubbleComponent = miniMessage.deserialize(format, Placeholder.parsed("message", message));

        removeBubble(player.getUniqueId());

        playerBubbles.put(player.getUniqueId(), new BubbleInstance(player, bubbleComponent));
    }

    private boolean hasPlayersNearby(@NotNull Player player, int radius) {
        // Paper's chunk-indexed nearby-entity lookup instead of scanning every player in the
        // world on every single chat message.
        return !player.getLocation().getNearbyPlayers(radius, p -> !p.equals(player)).isEmpty();
    }

    public void removeBubble(@NotNull UUID playerUuid) {
        BubbleInstance instance = playerBubbles.remove(playerUuid);
        if (instance != null) {
            instance.removeImmediately();
        }
    }

    public void clearAll() {
        for (BubbleInstance instance : playerBubbles.values()) {
            instance.removeImmediately();
        }
        playerBubbles.clear();
    }

    private class BubbleInstance {
        private final TextDisplay textDisplay;
        private final Player player;

        public BubbleInstance(@NotNull Player player, @NotNull Component component) {
            this.player = player;

            TextDisplay textDisplay = (TextDisplay) player.getWorld().spawnEntity(
                    player.getLocation(),
                    EntityType.TEXT_DISPLAY
            );

            textDisplay.text(component);
            textDisplay.setBillboard(Display.Billboard.CENTER);
            textDisplay.setSeeThrough(true);
            textDisplay.setShadowed(true);
            textDisplay.setDefaultBackground(true);
            textDisplay.setAlignment(TextDisplay.TextAlignment.CENTER);
            textDisplay.setLineWidth(200);
            textDisplay.setInterpolationDelay(0);
            textDisplay.setInterpolationDuration(10);

            Transformation startTransform = new Transformation(
                    new Vector3f(0, (float) heightOffset, 0),
                    new AxisAngle4f(0, 0, 0, 0),
                    new Vector3f(0.1f, 0.1f, 0.1f),
                    new AxisAngle4f(0, 0, 0, 0)
            );
            textDisplay.setTransformation(startTransform);

            Transformation endTransform = new Transformation(
                    new Vector3f(0, (float) heightOffset, 0),
                    new AxisAngle4f(0, 0, 0, 0),
                    new Vector3f(1.0f, 1.0f, 1.0f),
                    new AxisAngle4f(0, 0, 0, 0)
            );

            textDisplay.getScheduler().runDelayed(plugin, task -> {
                if (!textDisplay.isDead()) {
                    textDisplay.setTransformation(endTransform);
                }
            }, null, 1L);

            // 2. Добавили null перед displayTime
            textDisplay.getScheduler().runDelayed(plugin, task -> {
                if (!textDisplay.isDead()) {
                    Transformation fadeOutTransform = new Transformation(
                            new Vector3f(0, (float) heightOffset, 0),
                            new AxisAngle4f(0, 0, 0, 0),
                            new Vector3f(0.1f, 0.1f, 0.1f),
                            new AxisAngle4f(0, 0, 0, 0)
                    );
                    textDisplay.setTransformation(fadeOutTransform);

                    // 3. Добавили null перед 10L
                    textDisplay.getScheduler().runDelayed(plugin, t -> {
                        if (!textDisplay.isDead()) {
                            textDisplay.remove();
                            if (playerBubbles.get(player.getUniqueId()) == BubbleInstance.this) {
                                playerBubbles.remove(player.getUniqueId());
                            }
                        }
                    }, null, 10L);
                }
            }, null, displayTime);

            this.textDisplay = textDisplay;
            player.addPassenger(textDisplay);

            hideFromSilentPlayers(textDisplay);
        }

        private void hideFromSilentPlayers(@NotNull TextDisplay textDisplay) {
            Set<UUID> silentPlayers = plugin.getSilentPlayers();
            if (silentPlayers.isEmpty()) return;

            for (UUID silentUuid : silentPlayers) {
                Player silentPlayer = Bukkit.getPlayer(silentUuid);
                if (silentPlayer != null && silentPlayer.isOnline() &&
                        silentPlayer.getWorld().equals(player.getWorld())) {
                    if (silentPlayer.getLocation().distanceSquared(player.getLocation()) <= radius * radius) {
                        try {
                            silentPlayer.hideEntity(plugin, textDisplay);
                        } catch (Exception e) {
                            plugin.getLogger().log(java.util.logging.Level.WARNING,
                                    "Failed to hide chat bubble from " + silentPlayer.getName(), e);
                        }
                    }
                }
            }
        }

        public void removeImmediately() {
            if (textDisplay != null && !textDisplay.isDead()) {
                textDisplay.remove();
            }
        }
    }
}
