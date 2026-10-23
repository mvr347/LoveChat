package me.lovelace.lovechat.depends;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Where chat heads get their skin from ({@code integrations.skins} in config.yml).
 *
 * <p>Pure parsing only, so the fallback rules are unit-testable without a server:
 * a missing or blank value silently means the default ({@link #CMI}, what the plugin did before
 * the option existed); an unrecognised non-blank value also means the default, but is reported
 * through {@link Parsed#recognized()} so the caller can warn about the typo exactly once.</p>
 */
public enum SkinProviderMode {

    /** CMI reflection bridge (the historical behaviour). */
    CMI("cmi"),
    /** LoveCore {@code PresenceOracle} provider {@code loveessential}. */
    LOVEESSENTIAL("loveessential");

    public static final SkinProviderMode DEFAULT = CMI;

    /** Result of {@link #parse(String)}. {@code recognized} is false only for a non-blank unknown value. */
    public record Parsed(@NotNull SkinProviderMode mode, boolean recognized) {}

    private final String configId;

    SkinProviderMode(String configId) {
        this.configId = configId;
    }

    /** Lower-case id used in config.yml and as the LoveCore provider id. */
    public @NotNull String configId() {
        return configId;
    }

    /**
     * Value of {@code integrations.skins} when LoveCore cannot resolve it: {@code global} and blank mean
     * the historical default ({@link #CMI}), anything else stays as written.
     */
    public static @NotNull String withoutCore(@Nullable String raw) {
        if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("global")) {
            return DEFAULT.configId;
        }
        return raw;
    }

    public static @NotNull Parsed parse(@Nullable String raw) {
        if (raw == null) {
            return new Parsed(DEFAULT, true);
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return new Parsed(DEFAULT, true);
        }
        for (SkinProviderMode candidate : values()) {
            if (candidate.configId.equals(normalized)) {
                return new Parsed(candidate, true);
            }
        }
        return new Parsed(DEFAULT, false);
    }
}
