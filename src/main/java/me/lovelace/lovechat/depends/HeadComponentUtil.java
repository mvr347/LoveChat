package me.lovelace.lovechat.depends;

import net.kyori.adventure.text.Component;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Создает компонент головы игрока через NMS PlayerSprite (1.21.9+).
 * Использует reflection, чтобы не зависеть от NMS на этапе компиляции.
 */
public class HeadComponentUtil {

    public static Component createHeadComponent(UUID uuid, String name, String textureValue, String textureSignature) {
                    try {
                        Object resolvableProfile = buildResolvableProfile(uuid, name, textureValue, textureSignature);
                        if (resolvableProfile == null) {
                            debug("ResolvableProfile is null");
                            return null;
                        }

                        Class<?> playerSpriteClass = Class.forName("net.minecraft.network.chat.contents.objects.PlayerSprite");
                        Object sprite = null;
                        try {
                            Constructor<?> spriteCtor = playerSpriteClass.getConstructor(resolvableProfile.getClass(), boolean.class);
                            sprite = spriteCtor.newInstance(resolvableProfile, true);
                        } catch (NoSuchMethodException e) {
                            try {
                                Constructor<?> spriteCtor = playerSpriteClass.getConstructor(resolvableProfile.getClass());
                                sprite = spriteCtor.newInstance(resolvableProfile);
                            } catch (NoSuchMethodException ex) {
                                try {
                                    Constructor<?> spriteCtor = playerSpriteClass.getConstructors()[0];
                                    Class<?>[] params = spriteCtor.getParameterTypes();
                                    if (params.length == 2) {
                                        Object arg1 = params[0].isAssignableFrom(resolvableProfile.getClass()) ? resolvableProfile : null;
                                        Object arg2 = params[1] == boolean.class ? Boolean.TRUE : null;
                                        sprite = spriteCtor.newInstance(arg1, arg2);
                                    } else if (params.length == 1) {
                                        sprite = spriteCtor.newInstance(resolvableProfile);
                                    }
                                } catch (Exception ex2) {
                                    debug("PlayerSprite ctor not found");
                                    return null;
                                }
                            }
                        }

                        Class<?> nmsComponentClass = Class.forName("net.minecraft.network.chat.Component");
                        Method objectFactory = null;
                        for (Method m : nmsComponentClass.getMethods()) {
                            if (!m.getName().equals("object")) continue;
                            if (m.getParameterCount() == 1) {
                                objectFactory = m;
                                break;
                            }
                        }
                        if (objectFactory == null) return null;
                        Object nmsComponent = objectFactory.invoke(null, sprite);

                        Component adv = convertToAdventure(nmsComponent);
                        if (adv == null) debug("PaperAdventure.asAdventure returned null");
                        return adv;
                    } catch (Exception ignored) {
                        debug("Exception in createHeadComponent");
                        return null;
                    }
                }

                private static Object buildResolvableProfile(UUID uuid, String name, String textureValue, String textureSignature) {
                    try {
                        Class<?> resolvableProfileClass = Class.forName("net.minecraft.world.item.component.ResolvableProfile");
                        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");

                        if (textureValue != null && !textureValue.isEmpty()) {
                UUID id = makeSkinUuid(name);
                String profileName = makeSkinName(name);

                Class<?> propertyMapClass = Class.forName("com.mojang.authlib.properties.PropertyMap");
                Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
                            Object property = null;
                            if (textureSignature != null && !textureSignature.isEmpty()) {
                                try {
                                    property = propertyClass.getConstructor(String.class, String.class, String.class)
                                            .newInstance("textures", textureValue, textureSignature);
                                } catch (Exception ignored) {}
                            }
                            if (property == null) {
                                try {
                                    property = propertyClass.getConstructor(String.class, String.class)
                                            .newInstance("textures", textureValue);
                                } catch (Exception ignored) {}
                            }
                            if (property == null) {
                                try {
                                    property = propertyClass.getConstructor(String.class, String.class, String.class)
                                            .newInstance("textures", textureValue, null);
                                } catch (Exception ignored) {}
                            }
                            if (property == null) {
                                debug("Property ctor not found");
                                return null;
                            }

                Object propertyMap = createPropertyMapWithProperty(propertyMapClass, propertyClass, property);

                            Object gameProfile = createGameProfileWithProperties(gameProfileClass, id, profileName, propertyMap);
                            if (gameProfile != null) {
                                Method createResolved = findMethod(resolvableProfileClass, "createResolved", 1, gameProfileClass);
                                if (createResolved != null) {
                                    return createResolved.invoke(null, gameProfile);
                                } else {
                                    debug("ResolvableProfile.createResolved not found");
                                }
                            } else {
                                debug("GameProfile is null");
                            }
                        }

                        if (uuid != null) {
                            Method createUnresolved = findMethod(resolvableProfileClass, "createUnresolved", 1, UUID.class);
                            if (createUnresolved != null) return createUnresolved.invoke(null, uuid);
                        }

            if (name != null && !name.isEmpty()) {
                Method createUnresolved = findMethod(resolvableProfileClass, "createUnresolved", 1, String.class);
                if (createUnresolved != null) return createUnresolved.invoke(null, name);
            }
                    } catch (Exception ignored) {}

                    return null;
                }

    private static Object createPropertyMapWithProperty(Class<?> propertyMapClass, Class<?> propertyClass, Object property) {
        try {
            Object texturesKey = resolveTexturesKey(propertyMapClass);
            if (texturesKey != null) {
                debug("PropertyMap textures key: " + texturesKey.getClass().getName());
            }
            Object multimap = createImmutableMultimapWithProperty(property, texturesKey);
            if (multimap == null) {
                multimap = createHashMultimap();
                if (multimap != null) {
                    Method put = multimap.getClass().getMethod("put", Object.class, Object.class);
                    put.invoke(multimap, texturesKey != null ? texturesKey : "textures", property);
                }
            }

            if (multimap != null) {
                Constructor<?> ctor = findPropertyMapCtor(propertyMapClass);
                if (ctor != null) {
                    ctor.setAccessible(true);
                    Object map = ctor.newInstance(multimap);
                    debug("PropertyMap ctor(multimap) used");
                    return map;
                }
            }

            if (multimap != null) {
                for (String methodName : new String[]{"of", "create", "from"}) {
                    try {
                        Method m = propertyMapClass.getMethod(methodName, Class.forName("com.google.common.collect.Multimap"));
                        Object map = m.invoke(null, multimap);
                        debug("PropertyMap " + methodName + "(multimap) used");
                        return map;
                    } catch (Exception ignored) {}
                }
            }

            Object map = propertyMapClass.getConstructor().newInstance();
            boolean putOk = tryPutProperty(map, propertyClass, property);
            if (!putOk) debug("PropertyMap.put failed (new map)");
            return map;
        } catch (Exception ignored) {}
        return null;
    }

    private static Object createGameProfileWithProperties(Class<?> gameProfileClass, UUID id, String name, Object propertyMap) {
        try {
            if (propertyMap != null) {
                Constructor<?> ctor = findGameProfileCtorWithProperties(gameProfileClass);
                if (ctor != null) {
                    Object[] args = buildCtorArgs(ctor.getParameterTypes(), id, name, propertyMap);
                    if (args != null) {
                        debug("GameProfile ctor used: " + ctor.getParameterCount());
                        return ctor.newInstance(args);
                    }
                }
            }
        } catch (Exception ignored) {}

        try {
            Object gameProfile = gameProfileClass.getConstructor(UUID.class, String.class).newInstance(id, name);
            if (propertyMap != null && !setGameProfileProperties(gameProfile, propertyMap)) {
                debug("GameProfile properties field set failed");
            }
            return gameProfile;
        } catch (Exception ignored) {}
        return null;
    }

    private static Constructor<?> findGameProfileCtorWithProperties(Class<?> gameProfileClass) {
        for (Constructor<?> c : gameProfileClass.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length < 3) continue;
            if (params[0] != UUID.class) continue;
            if (params[1] != String.class) continue;
            if (!params[2].getName().equals("com.mojang.authlib.properties.PropertyMap")) continue;
            return c;
        }
        return null;
    }

    private static Object[] buildCtorArgs(Class<?>[] params, UUID id, String name, Object propertyMap) {
        Object[] args = new Object[params.length];
        args[0] = id;
        args[1] = name;
        args[2] = propertyMap;
        for (int i = 3; i < params.length; i++) {
            args[i] = defaultValue(params[i]);
        }
        return args;
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            if ("java.util.Optional".equals(type.getName())) {
                return java.util.Optional.empty();
            }
            return null;
        }
        if (type == boolean.class) return false;
        if (type == byte.class) return (byte) 0;
        if (type == short.class) return (short) 0;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == char.class) return (char) 0;
        return null;
    }

    private static boolean setGameProfileProperties(Object gameProfile, Object propertyMap) {
        try {
            Class<?> gpClass = gameProfile.getClass();
            java.lang.reflect.Field target = null;
            for (java.lang.reflect.Field f : gpClass.getDeclaredFields()) {
                if (f.getType().getName().equals("com.mojang.authlib.properties.PropertyMap")) {
                    target = f;
                    break;
                }
            }
            if (target != null) {
                target.setAccessible(true);
                target.set(gameProfile, propertyMap);
                debug("GameProfile properties field set");
                return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static Object createImmutableMultimapWithProperty(Object property, Object texturesKey) {
        try {
            Class<?> immutableMultimapClass = Class.forName("com.google.common.collect.ImmutableMultimap");
            Method builderMethod = immutableMultimapClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            Method put = builder.getClass().getMethod("put", Object.class, Object.class);
            put.invoke(builder, texturesKey != null ? texturesKey : "textures", property);
            Method build = builder.getClass().getMethod("build");
            return build.invoke(builder);
        } catch (Exception ignored) {}
        return null;
    }

    private static Constructor<?> findPropertyMapCtor(Class<?> propertyMapClass) {
        for (Constructor<?> c : propertyMapClass.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 1 && params[0].getName().equals("com.google.common.collect.Multimap")) {
                return c;
            }
        }
        return null;
    }

    private static Object createHashMultimap() {
        try {
            Class<?> hashMultimapClass = Class.forName("com.google.common.collect.HashMultimap");
            Method create = hashMultimapClass.getMethod("create");
            return create.invoke(null);
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean tryPutProperty(Object properties, Class<?> propertyClass, Object property) {
        Class<?> propsClass = properties.getClass();
        for (Method m : propsClass.getMethods()) {
            if (!m.getName().equals("put")) continue;
            if (m.getParameterCount() != 2) continue;
            try {
                m.setAccessible(true);
                Object key = resolvePropertyKey(m.getParameterTypes()[0], propsClass);
                if (key == null) key = "textures";
                m.invoke(properties, key, property);
                return true;
            } catch (Exception e) {
                Throwable cause = e.getCause();
                debug("PropertyMap.put failed: " + e.getClass().getSimpleName()
                        + " cause=" + (cause != null ? cause.getClass().getSimpleName() : "null"));
            }
        }

        try {
            Class<?> multimapClass = Class.forName("com.google.common.collect.Multimap");
            if (multimapClass.isAssignableFrom(propsClass)) {
                Method put = multimapClass.getMethod("put", Object.class, Object.class);
                Object key = resolvePropertyKey(Object.class, propsClass);
                if (key == null) key = "textures";
                put.invoke(properties, key, property);
                return true;
            }
        } catch (Exception ignored) {}

        try {
            Class<?> immutableMultimapClass = Class.forName("com.google.common.collect.ImmutableMultimap");
            Method builderMethod = immutableMultimapClass.getMethod("builder");
            Object builder = builderMethod.invoke(null);
            Method put = builder.getClass().getMethod("put", Object.class, Object.class);
            Object key = resolvePropertyKey(Object.class, propsClass);
            if (key == null) key = "textures";
            put.invoke(builder, key, property);
            Method build = builder.getClass().getMethod("build");
            Object multimap = build.invoke(builder);

            Method putAll = findMethodByName(propsClass, "putAll", 1, null);
            if (putAll != null) {
                putAll.setAccessible(true);
                putAll.invoke(properties, multimap);
                return true;
            }
        } catch (Exception ignored) {}

        Method addMethod = findMethodByName(propsClass, "add", 1, propertyClass);
        if (addMethod != null) {
            try {
                addMethod.setAccessible(true);
                addMethod.invoke(properties, property);
                return true;
            } catch (Exception ignored) {}
        }

        Method putAllMethod = findMethodByName(propsClass, "putAll", 1, null);
        if (putAllMethod != null) {
            try {
                putAllMethod.setAccessible(true);
                java.util.Map<Object, java.util.Collection<Object>> map = new java.util.HashMap<>();
                Object key = resolvePropertyKey(Object.class, propsClass);
                map.put(key != null ? key : "textures", java.util.Collections.singletonList(property));
                putAllMethod.invoke(properties, map);
                return true;
            } catch (Exception ignored) {}
        }

        putAllMethod = findMethodByName(propsClass, "putAll", 2, null);
        if (putAllMethod != null) {
            try {
                putAllMethod.setAccessible(true);
                Object list = java.util.Collections.singletonList(property);
                Object key = resolvePropertyKey(putAllMethod.getParameterTypes()[0], propsClass);
                if (key == null && putAllMethod.getParameterTypes()[0] == String.class) key = "textures";
                putAllMethod.invoke(properties, key, list);
                return true;
            } catch (Exception ignored) {}
        }

        debugMethods("PropertyMap", propsClass);
        return false;
    }

    private static Method findMethod(Class<?> type, String name, int paramCount, Class<?> paramType) {
        Method method = findMethodByName(type, name, paramCount, paramType);
        if (method != null) return method;
        for (Method m : type.getDeclaredMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramCount) continue;
            if (paramType == null) return m;
            if (m.getParameterTypes()[0].isAssignableFrom(paramType) || paramType.isAssignableFrom(m.getParameterTypes()[0])) {
                return m;
            }
        }
        return null;
    }

    private static Method findMethodByName(Class<?> type, String name, int paramCount, Class<?> paramType) {
        for (Method m : type.getMethods()) {
            if (!m.getName().equals(name)) continue;
            if (m.getParameterCount() != paramCount) continue;
            if (paramType == null) return m;
            if (m.getParameterTypes().length == 0) continue;
            if (paramType.isAssignableFrom(m.getParameterTypes()[0]) || m.getParameterTypes()[0].isAssignableFrom(paramType)) {
                return m;
            }
        }
        return null;
    }

    private static Object resolvePropertyKey(Class<?> keyType, Class<?> propsClass) {
        try {
            if (keyType == String.class || keyType == Object.class) {
                return "textures";
            }

            try {
                java.lang.reflect.Field f = propsClass.getField("TEXTURES");
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}

            try {
                java.lang.reflect.Field f = keyType.getField("TEXTURES");
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}

            for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
                try {
                    Method m = propsClass.getMethod(methodName, String.class);
                    Object v = m.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
                try {
                    Method m = keyType.getMethod(methodName, String.class);
                    Object v = m.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }

            try {
                Constructor<?> ctor = keyType.getConstructor(String.class);
                return ctor.newInstance("textures");
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return null;
    }

    private static Object resolveTexturesKey(Class<?> propertyMapClass) {
        try {
            try {
                java.lang.reflect.Field f = propertyMapClass.getField("TEXTURES");
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}

            for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
                try {
                    Method m = propertyMapClass.getMethod(methodName, String.class);
                    Object v = m.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }

            for (Class<?> nested : propertyMapClass.getDeclaredClasses()) {
                try {
                    java.lang.reflect.Field f = nested.getField("TEXTURES");
                    Object v = f.get(null);
                    if (v != null) return v;
                } catch (Exception ignored) {}

                for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
                    try {
                        Method m = nested.getMethod(methodName, String.class);
                        Object v = m.invoke(null, "textures");
                        if (v != null) return v;
                    } catch (Exception ignored) {}
                }

                try {
                    Constructor<?> ctor = nested.getConstructor(String.class);
                    Object v = ctor.newInstance("textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return null;
    }

    public static UUID makeSkinUuid(String name) {
        String safeName = (name != null && !name.isEmpty()) ? name : "Unknown";
        return UUID.nameUUIDFromBytes(("CustomSkin_" + safeName).getBytes(StandardCharsets.UTF_8));
    }

    public static String makeSkinName(String name) {
        String safeName = (name != null && !name.isEmpty()) ? name : "Unknown";
        return "Skin_" + safeName;
    }

    private static void debugMethods(String label, Class<?> type) {
        try {
            if (!me.lovelace.lovechat.Lovechat.getInstance().getConfig().getBoolean("general.debug", false)) return;
        } catch (Exception ignored) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[SkinDebug] ").append(label).append(" methods:");
        for (Method m : type.getMethods()) {
            if (m.getName().equals("put") || m.getName().equals("add") || m.getName().equals("putAll")) {
                sb.append(" ").append(m.getName()).append("(");
                Class<?>[] params = m.getParameterTypes();
                for (int i = 0; i < params.length; i++) {
                    if (i > 0) sb.append(",");
                    sb.append(params[i].getSimpleName());
                }
                sb.append(")");
            }
        }
        me.lovelace.lovechat.Lovechat.getInstance().getLogger().info(sb.toString());
    }

    private static Component convertToAdventure(Object nmsComponent) {
        try {
            Class<?> paperAdventureClass = Class.forName("io.papermc.paper.adventure.PaperAdventure");
            Method asAdventure = null;
            for (Method m : paperAdventureClass.getMethods()) {
                if (!m.getName().equals("asAdventure")) continue;
                if (m.getParameterCount() != 1) continue;
                if (m.getParameterTypes()[0].isAssignableFrom(nmsComponent.getClass())
                        || nmsComponent.getClass().isAssignableFrom(m.getParameterTypes()[0])) {
                    asAdventure = m;
                    break;
                }
            }
            if (asAdventure == null) {
                debug("PaperAdventure.asAdventure not found");
                return null;
            }
            Object result = asAdventure.invoke(null, nmsComponent);
            if (result instanceof Component comp) {
                return comp;
            }
        } catch (Exception ignored) {}
        Component fallback = convertToAdventureViaJson(nmsComponent);
        if (fallback != null) return fallback;
        return null;
    }

    private static Component convertToAdventureViaJson(Object nmsComponent) {
        try {
            Class<?> serializerClass = Class.forName("net.minecraft.network.chat.Component$Serializer");
            Method toJson = null;
            for (Method m : serializerClass.getMethods()) {
                if (!m.getName().equals("toJson")) continue;
                if (m.getParameterCount() != 1) continue;
                toJson = m;
                break;
            }
            if (toJson == null) return null;
            Object json = toJson.invoke(null, nmsComponent);
            if (json instanceof String s) {
                return net.kyori.adventure.text.serializer.gson.GsonComponentSerializer.gson().deserialize(s);
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static void debug(String msg) {
        try {
            if (!me.lovelace.lovechat.Lovechat.getInstance().getConfig().getBoolean("general.debug", false)) return;
            me.lovelace.lovechat.Lovechat.getInstance().getLogger().info("[SkinDebug] HeadComponentUtil: " + msg);
        } catch (Exception ignored) {}
    }
}
