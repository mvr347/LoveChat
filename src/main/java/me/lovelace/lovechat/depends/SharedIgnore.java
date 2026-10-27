package me.lovelace.lovechat.depends;

import java.lang.reflect.Method;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * The server-wide ignore list lives in LoveEssential (chat, private messages and requests share
 * it). It is reached reflectively so LoveChat needs no extra compile-time dependency; when the
 * methods are missing the plugin falls back to its own list.
 */
public final class SharedIgnore {
    private final Plugin owner;
    private final Method isIgnoring;
    private final Method importIgnore;
    private final Method setIgnored;

    private SharedIgnore(Plugin owner, Method isIgnoring, Method importIgnore, Method setIgnored) {
        this.owner = owner;
        this.isIgnoring = isIgnoring;
        this.importIgnore = importIgnore;
        this.setIgnored = setIgnored;
    }

    /** Looks the provider up each time it is needed: it may load after or reload before LoveChat. */
    public static SharedIgnore find() {
        Plugin owner = Bukkit.getPluginManager().getPlugin("LoveEssential");
        if (owner == null || !owner.isEnabled()) return null;
        try {
            Method ignoring = owner.getClass().getMethod("isIgnoring", UUID.class, UUID.class);
            Method imported = owner.getClass().getMethod("importIgnore", Player.class, UUID.class);
            Method set = owner.getClass().getMethod("setIgnored", Player.class, UUID.class, boolean.class);
            return new SharedIgnore(owner, ignoring, imported, set);
        } catch (ReflectiveOperationException missing) {
            return null;
        }
    }

    public boolean isIgnoring(UUID who, UUID target) {
        try {
            return (boolean) isIgnoring.invoke(owner, who, target);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
    }

    /** False means the shared list was not ready (still loading) and the entry must be retried. */
    public boolean importIgnore(Player who, UUID target) {
        try {
            return (boolean) importIgnore.invoke(owner, who, target);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
    }

    /** Adds or removes an entry in the shared list; false means it was not ready. */
    public boolean setIgnored(Player who, UUID target, boolean ignore) {
        try {
            return (boolean) setIgnored.invoke(owner, who, target, ignore);
        } catch (ReflectiveOperationException | RuntimeException failed) {
            return false;
        }
    }
}
