package me.lovelace.lovechat.depends;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Ordered "first usable answer wins" resolution used for skin lookups. Later (more expensive)
 * sources are never invoked once an earlier one produced a usable result, and a source that
 * throws counts as "no answer" instead of aborting the chain. Kept free of Bukkit types so the
 * ordering rules can be unit-tested.
 */
public final class SkinChain {

    private SkinChain() {}

    @SafeVarargs
    public static <T> T firstUsable(Predicate<? super T> usable, Supplier<? extends T>... sources) {
        for (Supplier<? extends T> source : sources) {
            T candidate;
            try {
                candidate = source.get();
            } catch (RuntimeException | LinkageError failed) {
                continue;
            }
            if (candidate != null && usable.test(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
