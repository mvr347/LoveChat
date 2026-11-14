package me.lovelace.lovechat.depends;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** A new texture needs a new client cache key, including when its owner has the same name. */
final class SkinIdentity {
    private SkinIdentity() {}

    static UUID id(String name, String texture) {
        return UUID.nameUUIDFromBytes(("CustomSkin_" + name + "\n" + texture).getBytes(StandardCharsets.UTF_8));
    }
}
