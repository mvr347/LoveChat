package me.lovelace.lovechat.depends;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Кэш reflection-объектов (Class/Method/Constructor/Field), которые не меняются
 * между вызовами в рамках одного запуска JVM. NMS reflection (HeadComponentUtil,
 * CMISkinUtil) раньше резолвился заново на каждое сообщение в чате для каждого
 * игрока — здесь резолв происходит один раз, дальше отдаётся кэш.
 * Логика поиска (fallback-цепочки) не меняется, кэшируется только сам факт
 * "JVM reflection lookup", а не финальный результат вызова invoke()/newInstance().
 */
public final class ReflectionCache {

    private ReflectionCache() {}

    private static final Map<String, Optional<Class<?>>> CLASSES = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method[]> DECLARED_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>[]> CONSTRUCTORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Constructor<?>[]> DECLARED_CONSTRUCTORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field[]> DECLARED_FIELDS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Class<?>[]> DECLARED_CLASSES = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Method>> METHOD = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Constructor<?>>> CONSTRUCTOR = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Field>> FIELD = new ConcurrentHashMap<>();
    private static final Map<String, Optional<Field>> DECLARED_FIELD = new ConcurrentHashMap<>();

    public static Class<?> findClass(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try {
                return Optional.of(Class.forName(n));
            } catch (ClassNotFoundException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static Method[] getMethods(Class<?> type) {
        return METHODS.computeIfAbsent(type, Class::getMethods);
    }

    public static Method[] getDeclaredMethods(Class<?> type) {
        return DECLARED_METHODS.computeIfAbsent(type, Class::getDeclaredMethods);
    }

    public static Constructor<?>[] getConstructors(Class<?> type) {
        return CONSTRUCTORS.computeIfAbsent(type, Class::getConstructors);
    }

    public static Constructor<?>[] getDeclaredConstructors(Class<?> type) {
        return DECLARED_CONSTRUCTORS.computeIfAbsent(type, Class::getDeclaredConstructors);
    }

    public static Field[] getDeclaredFields(Class<?> type) {
        return DECLARED_FIELDS.computeIfAbsent(type, Class::getDeclaredFields);
    }

    public static Class<?>[] getDeclaredClasses(Class<?> type) {
        return DECLARED_CLASSES.computeIfAbsent(type, Class::getDeclaredClasses);
    }

    public static Method getMethod(Class<?> type, String name, Class<?>... paramTypes) {
        return METHOD.computeIfAbsent(methodKey(type, name, paramTypes), k -> {
            try {
                return Optional.of(type.getMethod(name, paramTypes));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static Constructor<?> getConstructor(Class<?> type, Class<?>... paramTypes) {
        return CONSTRUCTOR.computeIfAbsent(ctorKey(type, paramTypes), k -> {
            try {
                return Optional.of(type.getConstructor(paramTypes));
            } catch (NoSuchMethodException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static Field getField(Class<?> type, String name) {
        return FIELD.computeIfAbsent(type.getName() + "#" + name, k -> {
            try {
                return Optional.of(type.getField(name));
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    public static Field getDeclaredField(Class<?> type, String name) {
        return DECLARED_FIELD.computeIfAbsent(type.getName() + "#" + name, k -> {
            try {
                return Optional.of(type.getDeclaredField(name));
            } catch (NoSuchFieldException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    private static String methodKey(Class<?> type, String name, Class<?>... paramTypes) {
        StringBuilder sb = new StringBuilder(type.getName()).append('#').append(name).append('(');
        for (Class<?> p : paramTypes) sb.append(p.getName()).append(',');
        return sb.append(')').toString();
    }

    private static String ctorKey(Class<?> type, Class<?>... paramTypes) {
        StringBuilder sb = new StringBuilder(type.getName()).append("#<init>(");
        for (Class<?> p : paramTypes) sb.append(p.getName()).append(',');
        return sb.append(')').toString();
    }

    /** Сбросить кэш — полезно при /lovechat reload, если NMS-классы переинициализируются. */
    public static void clear() {
        CLASSES.clear();
        METHODS.clear();
        DECLARED_METHODS.clear();
        CONSTRUCTORS.clear();
        DECLARED_CONSTRUCTORS.clear();
        DECLARED_FIELDS.clear();
        DECLARED_CLASSES.clear();
        METHOD.clear();
        CONSTRUCTOR.clear();
        FIELD.clear();
        DECLARED_FIELD.clear();
    }
}
