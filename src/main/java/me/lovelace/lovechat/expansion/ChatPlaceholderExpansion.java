package me.lovelace.lovechat.expansion;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import me.lovelace.lovechat.Lovechat;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class ChatPlaceholderExpansion extends PlaceholderExpansion {
    private final Lovechat plugin;

    public ChatPlaceholderExpansion(Lovechat plugin) { this.plugin = plugin; }

    @Override public @NotNull String getIdentifier() { return "lovechat"; }
    @Override public @NotNull String getAuthor() { return "Lovelace"; }
    @Override public @NotNull String getVersion() { return "3.0"; }
    @Override public boolean persist() { return true; }

    @Override public String onRequest(OfflinePlayer player, @NotNull String params) {
        Player onlinePlayer = player.getPlayer();

        switch (params) {
            case "channel": return onlinePlayer != null ? plugin.getDefaultChannel(player.getUniqueId()) : "unknown";
            case "message_id_last": return onlinePlayer != null ? String.valueOf(plugin.getLastMessageId(player.getUniqueId())) : "0";
            case "silent_mode": return onlinePlayer != null && plugin.isSilent(player.getUniqueId()) ? "true" : "false";
            case "spy_mode": return onlinePlayer != null && plugin.isSpy(player.getUniqueId()) ? "true" : "false";
            case "mentions_disabled": return onlinePlayer != null && plugin.hasTagsDisabled(player.getUniqueId()) ? "true" : "false";
            case "version": return "3.0";
            case "status": return "enabled";
            default:
        }
        return null;
    }
}