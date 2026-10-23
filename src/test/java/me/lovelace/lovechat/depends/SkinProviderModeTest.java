package me.lovelace.lovechat.depends;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SkinProviderModeTest {

    @Test
    void parsesKnownIdsCaseInsensitivelyAndTrimmed() {
        assertEquals(SkinProviderMode.CMI, SkinProviderMode.parse("cmi").mode());
        assertEquals(SkinProviderMode.LOVEESSENTIAL, SkinProviderMode.parse("loveessential").mode());
        assertEquals(SkinProviderMode.LOVEESSENTIAL, SkinProviderMode.parse("  LoveEssential ").mode());
        assertTrue(SkinProviderMode.parse("LOVEESSENTIAL").recognized());
    }

    @Test
    void missingOrBlankFallsBackToCmiSilently() {
        for (String raw : new String[] {null, "", "   "}) {
            var parsed = SkinProviderMode.parse(raw);
            assertEquals(SkinProviderMode.CMI, parsed.mode());
            assertTrue(parsed.recognized(), "absent key must not warn");
        }
    }

    @Test
    void unknownValueFallsBackToCmiAndIsFlagged() {
        var parsed = SkinProviderMode.parse("citizens");
        assertEquals(SkinProviderMode.CMI, parsed.mode());
        assertFalse(parsed.recognized());
    }

    @Test
    void withoutCoreGlobalAndBlankMeanCmiButAnExplicitValueStays() {
        assertEquals("cmi", SkinProviderMode.withoutCore(null));
        assertEquals("cmi", SkinProviderMode.withoutCore(" "));
        assertEquals("cmi", SkinProviderMode.withoutCore("Global"));
        assertEquals("loveessential", SkinProviderMode.withoutCore("loveessential"));
        assertEquals("citizens", SkinProviderMode.withoutCore("citizens"));
    }

    @Test
    void configIdsMatchTheLoveCoreProviderIds() {
        assertEquals("cmi", SkinProviderMode.CMI.configId());
        assertEquals("loveessential", SkinProviderMode.LOVEESSENTIAL.configId());
    }

    @Test
    void chainReturnsFirstUsableAndDoesNotCallLaterSources() {
        var laterCalls = new AtomicInteger();
        String result = SkinChain.firstUsable(s -> !s.isEmpty(),
                () -> "profile",
                () -> {
                    laterCalls.incrementAndGet();
                    return "provider";
                });
        assertEquals("profile", result);
        assertEquals(0, laterCalls.get());
    }

    @Test
    void chainSkipsNullEmptyAndThrowingSources() {
        String result = SkinChain.firstUsable(s -> !s.isEmpty(),
                () -> null,
                () -> "",
                () -> {
                    throw new IllegalStateException("provider down");
                },
                () -> "cache");
        assertEquals("cache", result);
    }

    @Test
    void chainWithNothingUsableGivesNull() {
        assertNull(SkinChain.firstUsable(s -> !s.isEmpty(), () -> null, () -> ""));
    }
}
