package me.lovelace.lovechat.managers;

import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatRedrawTest {

    private static String plain(Component c) {
        return PlainTextComponentSerializer.plainText().serialize(c);
    }

    @Test
    void redrawIsOneMessageWithClearLinesFirstAndHistoryInOrder() {
        List<ChatHistoryManager.ChatLine> lines = List.of(
                new ChatHistoryManager.ChatLine(1, Component.text("first"), false),
                new ChatHistoryManager.ChatLine(2, Component.text("second"), true),
                new ChatHistoryManager.ChatLine(3, Component.text("third"), false));

        String text = plain(ChatHistoryManager.buildRedraw(lines));

        assertTrue(text.startsWith("\n".repeat(100)), "the old client buffer is pushed out by 100 blank lines");
        assertEquals("first\nsecond\nthird", text.substring(100), "history follows, one line per entry, no trailing newline");
    }

    @Test
    void emptyHistoryStillClears() {
        assertEquals("\n".repeat(100), plain(ChatHistoryManager.buildRedraw(List.of())));
    }

    @Test
    void lineKeepsItsOwnEvents() {
        Component clickable = Component.text("x").clickEvent(MessageActions.deleteClick(7));
        Component redraw = ChatHistoryManager.buildRedraw(
                List.of(new ChatHistoryManager.ChatLine(1, clickable, false)));
        assertTrue(redraw.children().contains(clickable), "the line is appended as a child, with its click event intact");
    }

    @Test
    void messageIdPayloadRoundTrips() {
        assertEquals(42, MessageActions.parseId(BinaryTagHolder.binaryTagHolder("42")));
        assertEquals(42, MessageActions.parseId(BinaryTagHolder.binaryTagHolder("42i")));
        assertEquals(-1, MessageActions.parseId(BinaryTagHolder.binaryTagHolder("abc")));
        assertEquals(-1, MessageActions.parseId(BinaryTagHolder.binaryTagHolder("99999999999999")));
        assertEquals(-1, MessageActions.parseId(null));
        assertEquals(42, MessageActions.parseId(BinaryTagHolder.binaryTagHolder("{id:42}")));
    }
}
