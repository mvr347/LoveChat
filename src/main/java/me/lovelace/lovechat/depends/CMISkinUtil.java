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

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Утилита для работы со скинами CMI.
 * Позволяет получать скины игроков, установленные через /skin.
 */
@SuppressWarnings({"HttpUrlsUsage", "DuplicatedCode", "unused"})
public class CMISkinUtil {

    private static boolean cmiEnabled = false;
    private static final Cache<UUID, String> skinCache = CacheBuilder.newBuilder()
            .maximumSize(2000)
            .expireAfterAccess(2, TimeUnit.HOURS)
            .build();
    private static Object skinManagerInstance = null;
    private static Method getSkinByUuidMethod = null;
    private static Method getSkinByNameMethod = null;
    private static Class<?> skinManagerClass = null;

    public record SkinProperty(String value, String signature) {}

    public static void init() {
        if (Bukkit.getPluginManager().isPluginEnabled("CMI")) {
            cmiEnabled = true;
            try {
                // Инициализация CMI SkinManager
                Class<?> cmiClass = Class.forName("com.Zrips.CMI.CMI");
                Object cmiInstance = cmiClass.getMethod("getInstance").invoke(null);
                skinManagerInstance = cmiClass.getMethod("getSkinManager").invoke(cmiInstance);
                skinManagerClass = skinManagerInstance.getClass();
                
                // Пробуем найти метод getSkin(UUID)
                try {
                    getSkinByUuidMethod = skinManagerClass.getMethod("getSkin", UUID.class);
                    Lovechat.getInstance().getLogger().info("CMI найден! Поддержка скинов включена (UUID метод).");
                } catch (NoSuchMethodException e) {
                    // Пробуем альтернативные методы
                    try {
                        getSkinByUuidMethod = skinManagerClass.getMethod("getSkin", String.class);
                        Lovechat.getInstance().getLogger().info("CMI найден! Поддержка скинов включена (String метод).");
                    } catch (NoSuchMethodException ex) {
                        Lovechat.getInstance().getLogger().warning("CMI найден, но метод getSkin не доступен. Используем стандартные головы.");
                    }
                }
                
                try {
                    getSkinByNameMethod = skinManagerClass.getMethod("getSkin", String.class);
                } catch (NoSuchMethodException ignored) {}
                
            } catch (Throwable e) {
                // CMI's classes can reference optional dependencies (e.g. Vault) in method
                // signatures it doesn't itself have loaded; just calling getMethod() on such
                // a class throws NoClassDefFoundError (an Error, not Exception) when that
                // optional dependency isn't installed. Treat any failure here as "no CMI skins".
                Lovechat.getInstance().getLogger().warning("Ошибка инициализации CMI SkinManager: " + e.getMessage());
                cmiEnabled = false;
            }
        } else {
            cmiEnabled = false;
            Lovechat.getInstance().getLogger().info("CMI не найден. Будут использоваться стандартные головы.");
        }
    }

    public static boolean isCMIAvailable() {
        return cmiEnabled && skinManagerInstance != null;
    }

    public static ItemStack getPlayerHead(Player player) {
        ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);

        // Профиль игрока (актуален всегда, включая скины CMI, если CMI меняет сам GameProfile)
        // проверяется раньше CMI-рефлексии, а не только как её фолбэк.
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
                skullMeta.setOwningPlayer(player);
                head.setItemMeta(skullMeta);
            }
        } catch (Exception ignored) {}

        return head;
    }

    /**
     * Тонкая обёртка над {@link #getSkinProperty(UUID, String)}, чтобы не держать
     * два параллельных пути резолва скина (старый String-based и новый
     * SkinProperty-based). Профиль игрока проверяется раньше CMI (см. getSkinProperty).
     */
    public static String getSkinTexture(UUID uuid) {
        String cached = skinCache.getIfPresent(uuid);
        if (cached != null) {
            return cached;
        }
        Player player = Bukkit.getPlayer(uuid);
        SkinProperty prop = getSkinProperty(uuid, player != null ? player.getName() : null);
        if (prop != null && prop.value() != null && !prop.value().isEmpty()) {
            skinCache.put(uuid, prop.value());
            return prop.value();
        }
        return null;
    }

    public static SkinProperty getSkinProperty(UUID uuid, String playerName) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            SkinProperty fromProfile = getSkinPropertyFromProfile(player);
            if (fromProfile != null && fromProfile.value() != null && !fromProfile.value().isEmpty()) {
                return fromProfile;
            }
        }
        SkinProperty fromCmi = getSkinPropertyFromCmi(uuid, playerName);
        if (fromCmi != null && fromCmi.value() != null && !fromCmi.value().isEmpty()) {
            return fromCmi;
        }
        return null;
    }

    public static SkinProperty getSkinProperty(Player player) {
        SkinProperty fromProfile = getSkinPropertyFromProfile(player);
        if (fromProfile != null && fromProfile.value() != null && !fromProfile.value().isEmpty()) {
            return fromProfile;
        }
        return getSkinPropertyFromCmi(player.getUniqueId(), player.getName());
    }

    private static void setSkullTexture(SkullMeta skullMeta, String texture, UUID uuid) {
        try {
            Class<?> profileClass = Class.forName("com.mojang.authlib.GameProfile");
            Object profile = profileClass.getConstructor(UUID.class, String.class)
                    .newInstance(uuid, "ChatBubble_" + uuid.toString().substring(0, 8));
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object property = propertyClass.getConstructor(String.class, String.class)
                    .newInstance("textures", texture);
            Object propertyMap = profileClass.getMethod("getProperties").invoke(profile);
            propertyMap.getClass().getMethod("put", Object.class, Object.class)
                    .invoke(propertyMap, "textures", property);
            Method setProfileMethod = skullMeta.getClass().getDeclaredMethod("setProfile", profileClass);
            setProfileMethod.setAccessible(true);
            setProfileMethod.invoke(skullMeta, profile);
        } catch (Exception e) {
            try {
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
            } catch (Exception ignored) {}
        }
    }

    public static void clearCache() {
        skinCache.invalidateAll();
    }

    public static void removeFromCache(UUID uuid) {
        skinCache.invalidate(uuid);
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
        // CMI уже возвращает текстуру в Base64 формате
        return texture;
    }

    private static SkinProperty getSkinPropertyFromCmi(UUID uuid, String playerName) {
        if (!isCMIAvailable() || getSkinByUuidMethod == null) {
            return getSkinPropertyFromCmiCache(uuid, playerName);
        }

        try {
            SkinProperty cached = getSkinPropertyFromCmiCache(uuid, playerName);
            if (cached != null && cached.value() != null && !cached.value().isEmpty()) {
                skinCache.put(uuid, cached.value());
                logSkinDebug("CMI_CACHE", uuid, playerName, cached);
                return cached;
            }

            Object skin = null;

            if (getSkinByUuidMethod.getParameterCount() == 1 &&
                    getSkinByUuidMethod.getParameterTypes()[0] == UUID.class) {
                skin = getSkinByUuidMethod.invoke(skinManagerInstance, uuid);
            } else if (getSkinByUuidMethod.getParameterCount() == 1 &&
                    getSkinByUuidMethod.getParameterTypes()[0] == String.class) {
                skin = getSkinByUuidMethod.invoke(skinManagerInstance, uuid.toString());
            }

            if (skin == null && getSkinByNameMethod != null && playerName != null) {
                skin = getSkinByNameMethod.invoke(skinManagerInstance, playerName);
            }

            if (skin == null) {
                skin = tryResolveSkinByAnyMethod(uuid, playerName);
            }

            SkinProperty prop = extractSkinPropertyFromObject(skin);
            if (prop != null && prop.value() != null && !prop.value().isEmpty()) {
                skinCache.put(uuid, prop.value());
                logSkinDebug("CMI", uuid, playerName, prop);
                return prop;
            }
            logSkinDebugMiss("CMI", uuid, playerName, skin);
        } catch (Throwable ignored) {}

        return null;
    }

    private static SkinProperty getSkinPropertyFromCmiCache(UUID uuid, String playerName) {
        try {
            Class<?> cmiLibClass = Class.forName("net.Zrips.CMILib.CMILib");
            Object cmiLibInstance = cmiLibClass.getMethod("getInstance").invoke(null);
            Object skinManager = cmiLibClass.getMethod("getSkinManager").invoke(cmiLibInstance);
            if (skinManager == null) return null;

            Object skin = null;
            try {
                var byNameField = skinManager.getClass().getField("skinCacheByName");
                Object byNameObj = byNameField.get(skinManager);
                if (byNameObj instanceof java.util.Map<?, ?> byName && playerName != null) {
                    skin = byName.get(playerName);
                }
            } catch (NoSuchFieldException ignored) {}

            if (skin == null) {
                try {
                    var byUuidField = skinManager.getClass().getField("skinCacheByUUID");
                    Object byUuidObj = byUuidField.get(skinManager);
                    if (byUuidObj instanceof java.util.Map<?, ?> byUuid) {
                        skin = byUuid.get(uuid);
                    }
                } catch (NoSuchFieldException ignored) {}
            }

            SkinProperty prop = extractSkinPropertyFromObject(skin);
            if (prop != null && prop.value() != null && !prop.value().isEmpty()) {
                return prop;
            }
            logSkinDebugMiss("CMI_CACHE", uuid, playerName, skin);
        } catch (Throwable ignored) {}
        return null;
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

    private static String invokeSkinString(Class<?> skinClass, Object skin, String... methodNames) {
        for (String methodName : methodNames) {
            try {
                Method method = skinClass.getMethod(methodName);
                Object result = method.invoke(skin);
                if (result instanceof String str && !str.isEmpty()) {
                    return str;
                }
            } catch (NoSuchMethodException ignored) {
            } catch (Throwable e) {
                return null;
            }
        }
        return null;
    }

    private static Object tryResolveSkinByAnyMethod(UUID uuid, String playerName) {
        try {
            for (Method method : skinManagerClass.getMethods()) {
                String name = method.getName().toLowerCase();
                if (!name.contains("skin")) continue;
                if (method.getParameterCount() != 1) continue;
                Class<?> param = method.getParameterTypes()[0];
                try {
                    if (param == UUID.class) {
                        return method.invoke(skinManagerInstance, uuid);
                    }
                    if (param == String.class && playerName != null) {
                        return method.invoke(skinManagerInstance, playerName);
                    }
                    if (param == Player.class) {
                        Player p = Bukkit.getPlayer(uuid);
                        if (p != null) return method.invoke(skinManagerInstance, p);
                    }
                } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static SkinProperty extractSkinPropertyFromObject(Object skin) {
        if (skin == null) return null;

        if (skin instanceof String str && !str.isEmpty()) {
            return new SkinProperty(normalizeTextureValue(str), null);
        }

        Class<?> skinClass = skin.getClass();
        String className = skinClass.getName().toLowerCase();

        if (className.endsWith("property")) {
            String value = invokeSkinString(skinClass, skin, "getValue");
            if (value != null && !value.isEmpty()) {
                String signature = invokeSkinString(skinClass, skin, "getSignature");
                return new SkinProperty(normalizeTextureValue(value), signature);
            }
        }

        try {
            Method getProperties = skinClass.getMethod("getProperties");
            Object properties = getProperties.invoke(skin);
            SkinProperty fromProps = extractSkinPropertyFromProperties(properties);
            if (fromProps != null) return fromProps;
        } catch (Throwable ignored) {}

        try {
            Method getTextures = skinClass.getMethod("getTextures");
            Object textures = getTextures.invoke(skin);
            SkinProperty fromTextures = extractSkinPropertyFromProperties(textures);
            if (fromTextures != null) return fromTextures;
        } catch (Throwable ignored) {}

        String value = invokeSkinString(skinClass, skin, "getTexture", "getValue", "getSkin", "getBase64", "getData");
        if (value != null && !value.isEmpty()) {
            String signature = invokeSkinString(skinClass, skin, "getSignature", "getSkinSignature", "getSig");
            return new SkinProperty(normalizeTextureValue(value), signature);
        }

        try {
            var field = skinClass.getDeclaredField("value");
            field.setAccessible(true);
            Object val = field.get(skin);
            if (val instanceof String str && !str.isEmpty()) {
                String signature = null;
                try {
                    var sigField = skinClass.getDeclaredField("signature");
                    sigField.setAccessible(true);
                    Object sigVal = sigField.get(skin);
                    if (sigVal instanceof String sig && !sig.isEmpty()) signature = sig;
                } catch (Throwable ignored) {}
                return new SkinProperty(normalizeTextureValue(str), signature);
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private static SkinProperty extractSkinPropertyFromProperties(Object properties) {
        if (properties == null) return null;
        if (properties instanceof Iterable<?> iterable) {
            for (Object prop : iterable) {
                if (prop == null) continue;
                try {
                    String name = (String) prop.getClass().getMethod("getName").invoke(prop);
                    if (!"textures".equalsIgnoreCase(name)) continue;
                    String value = (String) prop.getClass().getMethod("getValue").invoke(prop);
                    String signature = null;
                    try {
                        signature = (String) prop.getClass().getMethod("getSignature").invoke(prop);
                    } catch (NoSuchMethodException ignored) {}
                    if (value != null && !value.isEmpty()) {
                        return new SkinProperty(normalizeTextureValue(value), signature);
                    }
                } catch (Throwable ignored) {}
            }
        }
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
     * Создать JSON компонент головы игрока с текстурой CMI
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
     * Получить URL текстуры скина игрока из CMI.
     * Возвращает прямой URL на PNG текстуру или null если не удалось.
     */
    @SuppressWarnings("unused")
    public static String getPlayerSkinUrl(Player player) {
        if (isCMIAvailable()) {
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
