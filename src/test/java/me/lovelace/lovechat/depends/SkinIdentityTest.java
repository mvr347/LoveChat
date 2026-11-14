package me.lovelace.lovechat.depends;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SkinIdentityTest {
    @Test void changingSkinInvalidatesClientIdentityWithoutChangingOtherPlayers() {
        var first = SkinIdentity.id("Alice", "skin-one");
        var second = SkinIdentity.id("Alice", "skin-two");
        assertNotEquals(first, second);
        assertEquals(first, SkinIdentity.id("Alice", "skin-one"));
        assertNotEquals(first, SkinIdentity.id("Bob", "skin-one"));
    }
}
