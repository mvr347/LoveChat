package me.lovelace.lovechat.managers;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnEchoesTest {

    private final UUID player = UUID.randomUUID();

    @Test
    void registeredTextIsConsumedOnce() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "hello", 0);
        assertTrue(echoes.consume(player, "hello", 1));
        assertFalse(echoes.consume(player, "hello", 2), "the entry must be gone after the first match");
    }

    @Test
    void foreignTextWithSameContentAfterOurEchoIsNotSwallowed() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "same", 0);
        assertTrue(echoes.consume(player, "same", 1));
        // Another plugin sends an equal line later: unlike a time window, this one must be recorded.
        assertFalse(echoes.consume(player, "same", 2));
    }

    @Test
    void unregisteredTextIsNotConsumed() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "ours", 0);
        assertFalse(echoes.consume(player, "theirs", 1));
        assertTrue(echoes.consume(player, "ours", 2), "a foreign line must not disturb our pending entry");
    }

    @Test
    void entriesMatchInAnyOrder() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "a", 0);
        echoes.register(player, "b", 0);
        assertTrue(echoes.consume(player, "b", 1));
        assertTrue(echoes.consume(player, "a", 1));
    }

    @Test
    void expiredEntryIsDropped() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "late", 0);
        assertFalse(echoes.consume(player, "late", OwnEchoes.TTL_NANOS + 1));
    }

    @Test
    void entriesArePerPlayer() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "x", 0);
        assertFalse(echoes.consume(UUID.randomUUID(), "x", 1));
        assertTrue(echoes.consume(player, "x", 1));
    }

    @Test
    void forgetDropsPendingEntries() {
        OwnEchoes echoes = new OwnEchoes();
        echoes.register(player, "x", 0);
        echoes.forget(player);
        assertFalse(echoes.consume(player, "x", 1));
    }
}
