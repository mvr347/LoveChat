package me.lovelace.lovechat.depends;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import me.lovelace.lovechat.Lovechat;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Skin resolution for chat heads. Despite the historical name (the class used to bridge CMI) it has one source
 * since 2026-11-21: LoveCore's {@code PresenceOracle} provided by LoveEssential. The player's own profile
 * textures are always checked first. The class name stays because {@code LovechatAPI#getCMISkinUtil} exposes it.
 */
@SuppressWarnings({"HttpUrlsUsage", "DuplicatedCode", "unused"})
public class CMISkinUtil {

    private static volatile boolean warnedProviderMissing = false;
    private static volatile boolean warnedProviderFailure = false;
    /**
     * Last skin seen per player. The PresenceOracle has no data for offline authors (history re-render,
     * offline-author path), so this is the fallback there.
     */
    private static final Cache<UUID, SkinProperty> lastKnownSkin = CacheBuilder.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private static final Cache<UUID, String> skinCache = CacheBuilder.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();

    public record SkinProperty(String value, String signature) {}

    public static void init() {
        warnedProviderMissing = false;
        warnedProviderFailure = false;
        clearCache();
        Lovechat.getInstance().getLogger().info("Источник скинов: loveessential (LoveCore PresenceOracle) и профиль игрока.");
        String leftover = Lovechat.getInstance().getConfig().getString("integrations.skins", null);
        if (leftover != null && !leftover.isBlank() && !leftover.trim().equalsIgnoreCase("global")
                && !leftover.trim().equalsIgnoreCase("loveessential")) {
            Lovechat.getInstance().getLogger().warning("integrations.skins: значение '" + leftover.trim()
                    + "' игнорируется, скины берутся только из LoveEssential; удалите ключ из config.yml.");
        }
    }

    /**
     * The player's styled nickname from LoveEssential's presence provider (/nick), or null when the
     * player has none. Chat shows it instead of the account name; the real name stays in hover and click.
     */
    public static @org.jetbrains.annotations.Nullable net.kyori.adventure.text.Component displayName(UUID uuid) {
        return PresenceSkinBridge.displayName(uuid);
    }

    /**
     * True when LoveEssential's presence provider is registered and can supply skins beyond the player's own
     * profile.
     */
    public static boolean isSkinProviderAvailable() {
        return PresenceSkinBridge.providerPresent();
    }

    /**
     * Kept so addons compiled against the old API do not fail with NoSuchMethodError.
     *
     * @deprecated CMI support was removed; forwards to {@link #isSkinProviderAvailable()}.
     */
    @Deprecated
    public static boolean isCMIAvailable() {
        return isSkinProviderAvailable();
    }

    public static ItemStack getPlayerHead(Player player) {
        ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);

        // Профиль игрока (актуален всегда) проверяется раньше провайдера, а не только как его фолбэк.
        SkinProperty prop = getSkinProperty(player);
        if (prop != null && prop.value() != null && !prop.value().isEmpty()) {
            try {
                SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
                if (skullMeta != null) {
                    setSkullTexture(skullMeta, prop.value(), player.getUniqueId());
                    head.setItemMeta(skullMeta);
                    return head;
                }
            } catch (Exception e) {
                Lovechat.getInstance().getLogger().warning("Ошибка при установке текстуры скина: " + e.getMessage());
            }
        }

        // Стандартная голова
        try {
            SkullMeta skullMeta = (SkullMeta) head.getItemMeta();
            if (skullMeta != null) {
                // A blank head is preferable to a Mojang lookup of the offline-mode UUID.
                if (player.getPlayerProfile().getTextures().getSkin() != null)
                    skullMeta.setPlayerProfile(player.getPlayerProfile());
                head.setItemMeta(skullMeta);
            }
        } catch (Exception ignored) {}

        return head;
    }

    /**
     * Тонкая обёртка над {@link #getSkinProperty(UUID, String)}, чтобы не держать
     * два параллельных пути резолва скина (старый String-based и новый
     * SkinProperty-based). Профиль игрока проверяется раньше провайдера (см. getSkinProperty).
     */
    public static String getSkinTexture(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        String cached = player == null ? skinCache.getIfPresent(uuid) : null;
        if (cached != null) {
            return cached;
        }
        SkinProperty prop = getSkinProperty(uuid, player != null ? player.getName() : null);
        if (prop != null && prop.value() != null && !prop.value().isEmpty()) {
            skinCache.put(uuid, prop.value());
            return prop.value();
        }
        return null;
    }

    public static SkinProperty getSkinProperty(UUID uuid, String playerName) {
        return resolve(Bukkit.getPlayer(uuid), uuid, playerName);
    }

    public static SkinProperty getSkinProperty(Player player) {
        return resolve(player, player.getUniqueId(), player.getName());
    }

    private static boolean isUsable(SkinProperty prop) {
        return prop.value() != null && !prop.value().isEmpty();
    }

    /** Player's own profile first (always current), then LoveEssential's presence provider, then the last seen skin. */
    private static SkinProperty resolve(Player player, UUID uuid, String playerName) {
        SkinProperty found = SkinChain.firstUsable(CMISkinUtil::isUsable,
                () -> player != null ? getSkinPropertyFromProfile(player) : null,
                () -> getSkinPropertyFromProvider(uuid));
        if (found != null) {
            lastKnownSkin.put(uuid, found);
            return found;
        }
        // Offline author or provider has nothing: last skin we saw for this player.
        return lastKnownSkin.getIfPresent(uuid);
    }

    private static SkinProperty getSkinPropertyFromProvider(UUID uuid) {
        try {
            PresenceSkinBridge.Lookup lookup = PresenceSkinBridge.skin(uuid);
            if (!lookup.providerPresent()) {
                if (!warnedProviderMissing) {
                    warnedProviderMissing = true;
                    Lovechat.getInstance().getLogger().warning("Провайдер PresenceOracle 'loveessential' не зарегистрирован "
                            + "в LoveCore (LoveCore/LoveEssential не установлен или ещё не включился). "
                            + "Скины берутся только из профиля игрока и кэша.");
                }
                return null;
            }
            if (lookup.value() == null || lookup.value().isEmpty()) {
                return null;
            }
            SkinProperty prop = new SkinProperty(normalizeTextureValue(lookup.value()), lookup.signature());
            logSkinDebug("LOVEESSENTIAL", uuid, null, prop);
            return prop;
        } catch (RuntimeException e) {
            if (!warnedProviderFailure) {
                warnedProviderFailure = true;
                Lovechat.getInstance().getLogger().warning("PresenceOracle 'loveessential': сбой при получении скина: "
                        + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            return null;
        }
    }

    private static void setSkullTexture(SkullMeta skullMeta, String texture, UUID uuid) {
        var profile = Bukkit.createProfile(SkinIdentity.id(uuid.toString(), texture), "LoveChatSkin");
        profile.setProperty(new com.destroystokyo.paper.profile.ProfileProperty("textures", texture));
        skullMeta.setPlayerProfile(profile);
    }

    public static void clearCache() {
        skinCache.invalidateAll();
        lastKnownSkin.invalidateAll();
    }

    public static void removeFromCache(UUID uuid) {
        skinCache.invalidate(uuid);
        lastKnownSkin.invalidate(uuid);
    }

    /**
     * Получить текстуру скина в формате Base64 для JSON компонента
     * @param uuid UUID игрока
     * @return Base64 текстура или null
     */
    public static String getSkinTextureBase64(UUID uuid) {
        String texture = getSkinTexture(uuid);
        if (texture == null || texture.isEmpty()) {
            return null;
        }
        return texture;
    }

    private static SkinProperty getSkinPropertyFromProfile(Player player) {
        try {
            Object profile = player.getClass().getMethod("getPlayerProfile").invoke(player);
            if (profile == null) return null;
            Object properties = profile.getClass().getMethod("getProperties").invoke(profile);
            if (properties instanceof Iterable<?> iterable) {
                for (Object property : iterable) {
                    if (property == null) continue;
                    String name = (String) property.getClass().getMethod("getName").invoke(property);
                    if (!"textures".equalsIgnoreCase(name)) continue;
                    String value = (String) property.getClass().getMethod("getValue").invoke(property);
                    String signature = null;
                    try {
                        signature = (String) property.getClass().getMethod("getSignature").invoke(property);
                    } catch (NoSuchMethodException ignored) {}
                    if (value != null && !value.isEmpty()) {
                        SkinProperty prop = new SkinProperty(normalizeTextureValue(value), signature);
                        logSkinDebug("PROFILE", player.getUniqueId(), player.getName(), prop);
                        return prop;
                    }
                }
            }
            logSkinDebugMiss("PROFILE", player.getUniqueId(), player.getName(), properties);
        } catch (Exception ignored) {}
        return null;
    }

    private static String normalizeTextureValue(String value) {
        String trimmed = value.trim();
        if (trimmed.isEmpty()) return trimmed;

        String decoded = tryDecodeBase64(trimmed);
        if (decoded != null && decoded.contains("\"textures\"")) {
            return trimmed;
        }

        if (trimmed.startsWith("{") && trimmed.contains("\"textures\"")) {
            return Base64.getEncoder().encodeToString(trimmed.getBytes(StandardCharsets.UTF_8));
        }

        String urlPrefixHttp = "http://textures.minecraft.net/texture/";
        String urlPrefixHttps = "https://textures.minecraft.net/texture/";
        if (trimmed.startsWith(urlPrefixHttp) || trimmed.startsWith(urlPrefixHttps)) {
            String hash = trimmed.substring(trimmed.lastIndexOf('/') + 1);
            return encodeTextureHash(hash);
        }

        if (trimmed.matches("^[0-9a-fA-F]{32,}$")) {
            return encodeTextureHash(trimmed);
        }

        return trimmed;
    }

    private static void logSkinDebug(String source, UUID uuid, String playerName, SkinProperty prop) {
        try {
            if (!Lovechat.getInstance().getConfig().getBoolean("general.debug", false)) return;
        } catch (Exception ignored) {
            return;
        }
        String value = prop.value();
        String signature = prop.signature();
        String valueKind;
        if (value == null || value.isEmpty()) {
            valueKind = "empty";
        } else if (value.startsWith("http://textures.minecraft.net/texture/")
                || value.startsWith("https://textures.minecraft.net/texture/")) {
            valueKind = "url";
        } else if (value.matches("^[0-9a-fA-F]{32,}$")) {
            valueKind = "hash";
        } else {
            String decoded = tryDecodeBase64(value);
            valueKind = (decoded != null && decoded.contains("\"textures\"")) ? "base64" : "unknown";
        }
        Lovechat.getInstance().getLogger().info(
                "[SkinDebug] source=" + source
                        + " player=" + (playerName != null ? playerName : "null")
                        + " uuid=" + uuid
                        + " valueKind=" + valueKind
                        + " valueLen=" + (value != null ? value.length() : 0)
                        + " signature=" + (signature != null && !signature.isEmpty())
        );
    }

    private static void logSkinDebugMiss(String source, UUID uuid, String playerName, Object skinObj) {
        try {
            if (!Lovechat.getInstance().getConfig().getBoolean("general.debug", false)) return;
        } catch (Exception ignored) {
            return;
        }
        String skinClass = (skinObj != null) ? skinObj.getClass().getName() : "null";
        Lovechat.getInstance().getLogger().info(
                "[SkinDebug] source=" + source
                        + " player=" + (playerName != null ? playerName : "null")
                        + " uuid=" + uuid
                        + " no-texture class=" + skinClass
        );
    }

    private static String encodeTextureHash(String hash) {
        String url = "http://textures.minecraft.net/texture/" + hash;
        String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + url + "\"}}}";
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static String tryDecodeBase64(String value) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            String decoded = new String(bytes, StandardCharsets.UTF_8);
            if (decoded.startsWith("{") && decoded.contains("textures")) {
                return decoded;
            }
        } catch (IllegalArgumentException ignored) {}
        return null;
    }

    /**
     * Создать JSON компонент головы игрока
     * Возвращает компонент с именем игрока (голова через type: player)
     * @param playerName Имя игрока
     * @param uuid UUID игрока
     * @return JSON строка для GsonComponentSerializer
     */
    public static String createHeadJsonWithSkin(String playerName, @SuppressWarnings("unused") UUID uuid) {
        // Простой формат - только имя игрока
        // Голова будет добавлена отдельно через Component
        return "{\"text\":\"" + playerName + "\"}";
    }

    /**
     * Получить URL текстуры скина игрока.
     * Возвращает прямой URL на PNG текстуру или null если не удалось.
     */
    @SuppressWarnings("unused")
    public static String getPlayerSkinUrl(Player player) {
        if (isSkinProviderAvailable()) {
            String texture = getSkinTexture(player.getUniqueId());
            if (texture != null && !texture.isEmpty()) {
                try {
                    // Декодируем Base64 чтобы получить JSON с URL текстуры
                    String decoded = new String(java.util.Base64.getDecoder().decode(texture));
                    // Парсим JSON чтобы получить URL
                    JsonObject json = JsonParser.parseString(decoded).getAsJsonObject();
                    return json.getAsJsonObject("textures")
                            .getAsJsonObject("SKIN")
                            .get("url")
                            .getAsString();
                } catch (Exception e) {
                    Lovechat.getInstance().getLogger().warning("Ошибка получения URL скина: " + e.getMessage());
                }
            }
        }
        return null;
    }
}
