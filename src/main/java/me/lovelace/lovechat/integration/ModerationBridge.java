package me.lovelace.lovechat.integration;

import java.util.UUID;
import org.bukkit.Bukkit;

/** Optional WebAdmin hot-state lookup; no database or network access. */
public final class ModerationBridge {
  private ModerationBridge() {}

  public static boolean muted(UUID id) {
    var owner = Bukkit.getPluginManager().getPlugin("LoveWebAdmin");
    if (owner == null || !owner.isEnabled()) return false;
    try {
      var service = owner.getClass().getMethod("getModerationService").invoke(owner);
      return service != null
          && Boolean.TRUE.equals(
              service.getClass().getMethod("isMuted", UUID.class).invoke(service, id));
    } catch (ReflectiveOperationException unavailable) {
      return false;
    }
  }
}
