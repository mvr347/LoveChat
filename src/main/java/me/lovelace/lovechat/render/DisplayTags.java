package me.lovelace.lovechat.render;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.display.DisplayPrefs;

import java.util.UUID;

/**
 * Clan tag and suffix inside the chat format. The format may contain {@code <clan>} and {@code <suffix>};
 * each stands for the author's tag or suffix and disappears, with its decoration, when the author or the
 * viewer hid it (LoveCore {@link DisplayPrefs}, chosen with /display in LoveEssential).
 *
 * <p>Pure helpers only, so the rules are testable without a server.</p>
 */
final class DisplayTags {

    /** Bit set in a mask when the clan tag is left out. */
    static final int HIDE_CLAN = 1;
    /** Bit set in a mask when the suffix is left out. */
    static final int HIDE_SUFFIX = 2;

    private DisplayTags() {
    }

    /** Whether the format has a tag that the personal choice can remove. */
    static boolean hasTags(String format) {
        return format != null && (format.contains("<clan>") || format.contains("<suffix>"));
    }

    static int mask(boolean clanVisible, boolean suffixVisible) {
        return (clanVisible ? 0 : HIDE_CLAN) | (suffixVisible ? 0 : HIDE_SUFFIX);
    }

    /**
     * Wraps a value in its decoration, e.g. {@code "<gray>[</gray>%tag%<gray>]</gray> "}. An empty value gives an
     * empty text, so a player without a clan does not get empty brackets.
     */
    static String decorate(String template, String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String pattern = template == null || template.isEmpty() ? "%tag%" : template;
        return pattern.replace("%tag%", value);
    }

    /**
     * Which elements the viewer does not see on an author's message. Without LoveCore or without the display
     * service everything is shown, as before.
     */
    static int hiddenFor(UUID author, UUID viewer) {
        try {
            boolean clan = LoveCore.displayVisible(author, viewer, DisplayPrefs.Surface.CHAT, DisplayPrefs.Element.CLAN_TAG);
            boolean suffix = LoveCore.displayVisible(author, viewer, DisplayPrefs.Surface.CHAT, DisplayPrefs.Element.SUFFIX);
            return mask(clan, suffix);
        } catch (LinkageError coreMissingOrOld) {
            return 0;
        }
    }
}
