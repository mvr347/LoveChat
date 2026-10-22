package me.lovelace.lovechat.depends;

import dev.lovelace.lovecore.api.LoveCore;
import dev.lovelace.lovecore.api.presence.PresenceOracle;
import dev.lovelace.lovecore.api.presence.SkinTexture;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * Thin, isolated wrapper over LoveCore's {@link PresenceOracle}. It lives in its own class so that
 * LoveCore types are only linked when this class is first touched: if LoveCore is not installed
 * (it is a soft dependency) the {@link LinkageError} is caught here and reported as "provider missing",
 * instead of breaking {@link CMISkinUtil} as a whole.
 *
 * <p>The provider is resolved on every call and never cached in a field: providers register late
 * (LoveEssential may enable after LoveChat) and can be re-registered on reload.</p>
 */
final class PresenceSkinBridge {

    private PresenceSkinBridge() {}

    /**
     * @param providerPresent whether a PresenceOracle with the requested id is registered right now
     * @param value           base64 texture value, or null when the provider has no skin for the player
     * @param signature       texture signature, may be null
     */
    record Lookup(boolean providerPresent, @Nullable String value, @Nullable String signature) {
        static final Lookup MISSING = new Lookup(false, null, null);
    }

    static boolean providerPresent(@NotNull String providerId) {
        try {
            return LoveCore.provider(PresenceOracle.class, providerId).isPresent();
        } catch (LinkageError loveCoreAbsent) {
            return false;
        }
    }

    static @NotNull Lookup skin(@NotNull String providerId, @NotNull UUID playerId) {
        try {
            Optional<PresenceOracle> oracle = LoveCore.provider(PresenceOracle.class, providerId);
            if (oracle.isEmpty()) {
                return Lookup.MISSING;
            }
            Optional<SkinTexture> texture = oracle.get().skin(playerId);
            return texture
                    .map(t -> new Lookup(true, t.value(), t.signature()))
                    .orElseGet(() -> new Lookup(true, null, null));
        } catch (LinkageError loveCoreAbsent) {
            return Lookup.MISSING;
        }
    }
}
