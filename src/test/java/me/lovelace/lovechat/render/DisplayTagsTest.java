package me.lovelace.lovechat.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

class DisplayTagsTest {

    @Test
    void theMaskCarriesOneBitPerHiddenElement() {
        assertEquals(0, DisplayTags.mask(true, true));
        assertEquals(DisplayTags.HIDE_CLAN, DisplayTags.mask(false, true));
        assertEquals(DisplayTags.HIDE_SUFFIX, DisplayTags.mask(true, false));
        assertEquals(DisplayTags.HIDE_CLAN | DisplayTags.HIDE_SUFFIX, DisplayTags.mask(false, false));
    }

    @Test
    void anEmptyValueLeavesNoDecorationBehind() {
        assertEquals("", DisplayTags.decorate("<gray>[</gray>%tag%<gray>]</gray> ", ""));
        assertEquals("", DisplayTags.decorate("[%tag%] ", "  "));
        assertEquals("", DisplayTags.decorate("[%tag%] ", null));
    }

    @Test
    void aValueIsPlacedInsideItsDecoration() {
        assertEquals("[ABC] ", DisplayTags.decorate("[%tag%] ", "ABC"));
        assertEquals("ABC", DisplayTags.decorate("", "ABC"));
        assertEquals("ABC", DisplayTags.decorate(null, "ABC"));
    }

    @Test
    void onlyAFormatWithTheTagsCanBeChangedByThePlayersChoice() {
        assertTrue(DisplayTags.hasTags("<clan><player>: <message>"));
        assertTrue(DisplayTags.hasTags("<player><suffix>: <message>"));
        assertFalse(DisplayTags.hasTags("<player>: <message>"));
        assertFalse(DisplayTags.hasTags(null));
    }

    /** The same format, parsed the way the renderer does, with the tags swapped for nothing when hidden. */
    private static String render(String format, boolean clanVisible, boolean suffixVisible) {
        Component clan = clanVisible ? MiniMessage.miniMessage().deserialize(DisplayTags.decorate("[%tag%] ", "ABC")) : Component.empty();
        Component suffix = suffixVisible ? MiniMessage.miniMessage().deserialize(DisplayTags.decorate(" %tag%", "Rich")) : Component.empty();
        return PlainTextComponentSerializer.plainText().serialize(MiniMessage.miniMessage().deserialize(format,
                Placeholder.component("player", Component.text("Alice")),
                Placeholder.component("message", Component.text("hi")),
                Placeholder.component("clan", clan),
                Placeholder.component("suffix", suffix)));
    }

    @Test
    void hiddenTagsTakeTheirDecorationWithThem() {
        String format = "<clan><player><suffix>: <message>";
        assertEquals("[ABC] Alice Rich: hi", render(format, true, true));
        assertEquals("Alice Rich: hi", render(format, false, true));
        assertEquals("[ABC] Alice: hi", render(format, true, false));
        assertEquals("Alice: hi", render(format, false, false));
    }
}
