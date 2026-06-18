package me.lovelace.lovechat.depends;

import net.kyori.adventure.text.Component;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Создает компонент головы игрока через NMS PlayerSprite (1.21.9+).
 * Использует reflection, чтобы не зависеть от NMS на этапе компиляции.
 * Все Class/Method/Constructor lookups идут через {@link ReflectionCache},
 * поэтому реальный reflection-поиск выполняется только один раз за всё время
 * работы плагина, а не на каждое сообщение в чате.
 */
public class HeadComponentUtil {

    public static Component createHeadComponent(UUID uuid, String name, String textureValue, String textureSignature) {
        try {
            Object resolvableProfile = buildResolvableProfile(uuid, name, textureValue, textureSignature);
            if (resolvableProfile == null) {
                debug("ResolvableProfile is null");
                return null;
            }

            Class<?> playerSpriteClass = ReflectionCache.findClass("net.minecraft.network.chat.contents.objects.PlayerSprite");
            if (playerSpriteClass == null) {
                debug("PlayerSprite class not found");
                return null;
            }

            Object sprite = null;
            Constructor<?> spriteCtorWithFlag = ReflectionCache.getConstructor(playerSpriteClass, resolvableProfile.getClass(), boolean.class);
            if (spriteCtorWithFlag != null) {
                sprite = spriteCtorWithFlag.newInstance(resolvableProfile, true);
            } else {
                Constructor<?> spriteCtorPlain = ReflectionCache.getConstructor(playerSpriteClass, resolvableProfile.getClass());
                if (spriteCtorPlain != null) {
                    sprite = spriteCtorPlain.newInstance(resolvableProfile);
                } else {
                    Constructor<?>[] ctors = ReflectionCache.getConstructors(playerSpriteClass);
                    if (ctors.length > 0) {
                        Constructor<?> fallbackCtor = ctors[0];
                        Class<?>[] params = fallbackCtor.getParameterTypes();
                        if (params.length == 2) {
                            Object arg1 = params[0].isAssignableFrom(resolvableProfile.getClass()) ? resolvableProfile : null;
                            Object arg2 = params[1] == boolean.class ? Boolean.TRUE : null;
                            sprite = fallbackCtor.newInstance(arg1, arg2);
                        } else if (params.length == 1) {
                            sprite = fallbackCtor.newInstance(resolvableProfile);
                        }
                    }
                    if (sprite == null) {
                        debug("PlayerSprite ctor not found");
                        return null;
                    }
                }
            }

            Class<?> nmsComponentClass = ReflectionCache.findClass("net.minecraft.network.chat.Component");
            if (nmsComponentClass == null) return null;

            Method objectFactory = null;
            for (Method m : ReflectionCache.getMethods(nmsComponentClass)) {
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
            Class<?> resolvableProfileClass = ReflectionCache.findClass("net.minecraft.world.item.component.ResolvableProfile");
            Class<?> gameProfileClass = ReflectionCache.findClass("com.mojang.authlib.GameProfile");
            if (resolvableProfileClass == null || gameProfileClass == null) return null;

            if (textureValue != null && !textureValue.isEmpty()) {
                UUID id = makeSkinUuid(name);
                String profileName = makeSkinName(name);

                Class<?> propertyMapClass = ReflectionCache.findClass("com.mojang.authlib.properties.PropertyMap");
                Class<?> propertyClass = ReflectionCache.findClass("com.mojang.authlib.properties.Property");
                if (propertyMapClass == null || propertyClass == null) return null;

                Object property = null;
                if (textureSignature != null && !textureSignature.isEmpty()) {
                    Constructor<?> ctor3 = ReflectionCache.getConstructor(propertyClass, String.class, String.class, String.class);
                    if (ctor3 != null) {
                        try {
                            property = ctor3.newInstance("textures", textureValue, textureSignature);
                        } catch (Exception ignored) {}
                    }
                }
                if (property == null) {
                    Constructor<?> ctor2 = ReflectionCache.getConstructor(propertyClass, String.class, String.class);
                    if (ctor2 != null) {
                        try {
                            property = ctor2.newInstance("textures", textureValue);
                        } catch (Exception ignored) {}
                    }
                }
                if (property == null) {
                    Constructor<?> ctor3Null = ReflectionCache.getConstructor(propertyClass, String.class, String.class, String.class);
                    if (ctor3Null != null) {
                        try {
                            property = ctor3Null.newInstance("textures", textureValue, null);
                        } catch (Exception ignored) {}
                    }
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
                    Method put = ReflectionCache.getMethod(multimap.getClass(), "put", Object.class, Object.class);
                    if (put == null) return null;
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
                Class<?> multimapIface = ReflectionCache.findClass("com.google.common.collect.Multimap");
                if (multimapIface != null) {
                    for (String methodName : new String[]{"of", "create", "from"}) {
                        Method m = ReflectionCache.getMethod(propertyMapClass, methodName, multimapIface);
                        if (m == null) continue;
                        try {
                            Object map = m.invoke(null, multimap);
                            debug("PropertyMap " + methodName + "(multimap) used");
                            return map;
                        } catch (Exception ignored) {}
                    }
                }
            }

            Constructor<?> noArgCtor = ReflectionCache.getConstructor(propertyMapClass);
            if (noArgCtor == null) return null;
            Object map = noArgCtor.newInstance();
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
            Constructor<?> basicCtor = ReflectionCache.getConstructor(gameProfileClass, UUID.class, String.class);
            if (basicCtor == null) return null;
            Object gameProfile = basicCtor.newInstance(id, name);
            if (propertyMap != null && !setGameProfileProperties(gameProfile, propertyMap)) {
                debug("GameProfile properties field set failed");
            }
            return gameProfile;
        } catch (Exception ignored) {}
        return null;
    }

    private static Constructor<?> findGameProfileCtorWithProperties(Class<?> gameProfileClass) {
        for (Constructor<?> c : ReflectionCache.getConstructors(gameProfileClass)) {
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
            Field target = null;
            for (Field f : ReflectionCache.getDeclaredFields(gpClass)) {
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
            Class<?> immutableMultimapClass = ReflectionCache.findClass("com.google.common.collect.ImmutableMultimap");
            if (immutableMultimapClass == null) return null;
            Method builderMethod = ReflectionCache.getMethod(immutableMultimapClass, "builder");
            if (builderMethod == null) return null;
            Object builder = builderMethod.invoke(null);
            Method put = ReflectionCache.getMethod(builder.getClass(), "put", Object.class, Object.class);
            if (put == null) return null;
            put.invoke(builder, texturesKey != null ? texturesKey : "textures", property);
            Method build = ReflectionCache.getMethod(builder.getClass(), "build");
            if (build == null) return null;
            return build.invoke(builder);
        } catch (Exception ignored) {}
        return null;
    }

    private static Constructor<?> findPropertyMapCtor(Class<?> propertyMapClass) {
        for (Constructor<?> c : ReflectionCache.getDeclaredConstructors(propertyMapClass)) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 1 && params[0].getName().equals("com.google.common.collect.Multimap")) {
                return c;
            }
        }
        return null;
    }

    private static Object createHashMultimap() {
        try {
            Class<?> hashMultimapClass = ReflectionCache.findClass("com.google.common.collect.HashMultimap");
            if (hashMultimapClass == null) return null;
            Method create = ReflectionCache.getMethod(hashMultimapClass, "create");
            if (create == null) return null;
            return create.invoke(null);
        } catch (Exception ignored) {}
        return null;
    }

    private static boolean tryPutProperty(Object properties, Class<?> propertyClass, Object property) {
        Class<?> propsClass = properties.getClass();
        for (Method m : ReflectionCache.getMethods(propsClass)) {
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
            Class<?> multimapClass = ReflectionCache.findClass("com.google.common.collect.Multimap");
            if (multimapClass != null && multimapClass.isAssignableFrom(propsClass)) {
                Method put = ReflectionCache.getMethod(multimapClass, "put", Object.class, Object.class);
                if (put != null) {
                    Object key = resolvePropertyKey(Object.class, propsClass);
                    if (key == null) key = "textures";
                    put.invoke(properties, key, property);
                    return true;
                }
            }
        } catch (Exception ignored) {}

        try {
            Class<?> immutableMultimapClass = ReflectionCache.findClass("com.google.common.collect.ImmutableMultimap");
            if (immutableMultimapClass != null) {
                Method builderMethod = ReflectionCache.getMethod(immutableMultimapClass, "builder");
                if (builderMethod != null) {
                    Object builder = builderMethod.invoke(null);
                    Method put = ReflectionCache.getMethod(builder.getClass(), "put", Object.class, Object.class);
                    if (put != null) {
                        Object key = resolvePropertyKey(Object.class, propsClass);
                        if (key == null) key = "textures";
                        put.invoke(builder, key, property);
                        Method build = ReflectionCache.getMethod(builder.getClass(), "build");
                        if (build != null) {
                            Object multimap = build.invoke(builder);

                            Method putAll = findMethodByName(propsClass, "putAll", 1, null);
                            if (putAll != null) {
                                putAll.setAccessible(true);
                                putAll.invoke(properties, multimap);
                                return true;
                            }
                        }
                    }
                }
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
        for (Method m : ReflectionCache.getDeclaredMethods(type)) {
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
        for (Method m : ReflectionCache.getMethods(type)) {
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
        if (keyType == String.class || keyType == Object.class) {
            return "textures";
        }

        Field propsField = ReflectionCache.getField(propsClass, "TEXTURES");
        if (propsField != null) {
            try {
                Object v = propsField.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}
        }

        Field keyField = ReflectionCache.getField(keyType, "TEXTURES");
        if (keyField != null) {
            try {
                Object v = keyField.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}
        }

        for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
            Method propsMethod = ReflectionCache.getMethod(propsClass, methodName, String.class);
            if (propsMethod != null) {
                try {
                    Object v = propsMethod.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
            Method keyMethod = ReflectionCache.getMethod(keyType, methodName, String.class);
            if (keyMethod != null) {
                try {
                    Object v = keyMethod.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
        }

        Constructor<?> ctor = ReflectionCache.getConstructor(keyType, String.class);
        if (ctor != null) {
            try {
                return ctor.newInstance("textures");
            } catch (Exception ignored) {}
        }
        return null;
    }

    private static Object resolveTexturesKey(Class<?> propertyMapClass) {
        Field f = ReflectionCache.getField(propertyMapClass, "TEXTURES");
        if (f != null) {
            try {
                Object v = f.get(null);
                if (v != null) return v;
            } catch (Exception ignored) {}
        }

        for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
            Method m = ReflectionCache.getMethod(propertyMapClass, methodName, String.class);
            if (m == null) continue;
            try {
                Object v = m.invoke(null, "textures");
                if (v != null) return v;
            } catch (Exception ignored) {}
        }

        for (Class<?> nested : ReflectionCache.getDeclaredClasses(propertyMapClass)) {
            Field nestedField = ReflectionCache.getField(nested, "TEXTURES");
            if (nestedField != null) {
                try {
                    Object v = nestedField.get(null);
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }

            for (String methodName : new String[]{"key", "of", "valueOf", "create"}) {
                Method nestedMethod = ReflectionCache.getMethod(nested, methodName, String.class);
                if (nestedMethod == null) continue;
                try {
                    Object v = nestedMethod.invoke(null, "textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }

            Constructor<?> nestedCtor = ReflectionCache.getConstructor(nested, String.class);
            if (nestedCtor != null) {
                try {
                    Object v = nestedCtor.newInstance("textures");
                    if (v != null) return v;
                } catch (Exception ignored) {}
            }
        }
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
        for (Method m : ReflectionCache.getMethods(type)) {
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
            Class<?> paperAdventureClass = ReflectionCache.findClass("io.papermc.paper.adventure.PaperAdventure");
            if (paperAdventureClass == null) throw new ClassNotFoundException();
            Method asAdventure = null;
            for (Method m : ReflectionCache.getMethods(paperAdventureClass)) {
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
            Class<?> serializerClass = ReflectionCache.findClass("net.minecraft.network.chat.Component$Serializer");
            if (serializerClass == null) return null;
            Method toJson = null;
            for (Method m : ReflectionCache.getMethods(serializerClass)) {
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
