package me.lovelace.lovechat.utils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites legacy '§'-formatting codes into their MiniMessage tag equivalents.
 *
 * <p>Chat formats are expanded through PlaceholderAPI before being handed to
 * {@code MiniMessage.deserialize()}. Any expansion that returns legacy-colored text —
 * a LuckPerms prefix, a nickname plugin, anything that predates MiniMessage — throws
 * {@code ParsingException} at parse time, because MiniMessage 4.x rejects raw legacy
 * codes outright rather than silently accepting them. That takes the whole chat message
 * down for every player in range, not just the one with the offending placeholder.
 *
 * <p>Only the legacy-code syntax ('§' + hex digit, and the six-pair '§x' hex-color run)
 * is touched; MiniMessage's own '&lt;tag&gt;' syntax is left completely alone, so this is
 * safe to run over an entire format string that mixes both.
 */
public final class LegacyCodeSanitizer {
    private LegacyCodeSanitizer() {}

    private static final Pattern LEGACY_HEX = Pattern.compile(
            "§[xX](§[0-9a-fA-F]){6}");
    private static final Pattern LEGACY_CODE = Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private static final String[] NAMED_COLORS = {
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple",
            "yellow", "white"
    };

    public static String toMiniMessage(String text) {
        if (text == null || text.indexOf('§') < 0) return text;

        Matcher hexMatcher = LEGACY_HEX.matcher(text);
        StringBuilder afterHex = new StringBuilder();
        while (hexMatcher.find()) {
            String run = hexMatcher.group();
            StringBuilder hex = new StringBuilder("#");
            // "§x§8§b§5§c§f§6" -> drop every '§' -> "x8b5cf6" -> drop the leading 'x'
            for (int i = 3; i < run.length(); i += 2) {
                hex.append(run.charAt(i));
            }
            hexMatcher.appendReplacement(afterHex, Matcher.quoteReplacement("<" + hex + ">"));
        }
        hexMatcher.appendTail(afterHex);

        Matcher codeMatcher = LEGACY_CODE.matcher(afterHex);
        StringBuilder result = new StringBuilder();
        while (codeMatcher.find()) {
            char code = Character.toLowerCase(codeMatcher.group().charAt(1));
            codeMatcher.appendReplacement(result, Matcher.quoteReplacement(tagFor(code)));
        }
        codeMatcher.appendTail(result);
        return result.toString();
    }

    private static String tagFor(char code) {
        return switch (code) {
            case '0', '1', '2', '3', '4', '5', '6', '7', '8', '9' -> "<" + NAMED_COLORS[code - '0'] + ">";
            case 'a' -> "<" + NAMED_COLORS[10] + ">";
            case 'b' -> "<" + NAMED_COLORS[11] + ">";
            case 'c' -> "<" + NAMED_COLORS[12] + ">";
            case 'd' -> "<" + NAMED_COLORS[13] + ">";
            case 'e' -> "<" + NAMED_COLORS[14] + ">";
            case 'f' -> "<" + NAMED_COLORS[15] + ">";
            case 'k' -> "<obfuscated>";
            case 'l' -> "<bold>";
            case 'm' -> "<strikethrough>";
            case 'n' -> "<underlined>";
            case 'o' -> "<italic>";
            case 'r' -> "<reset>";
            default -> "";
        };
    }
}
