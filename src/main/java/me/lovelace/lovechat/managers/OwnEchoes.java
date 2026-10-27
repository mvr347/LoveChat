package me.lovelace.lovechat.managers;

import org.jetbrains.annotations.NotNull;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plain texts of the messages LoveChat itself sent to a player and whose outgoing packet has not
 * been seen by the packet hook yet. The sender registers a text before sending; the hook, on
 * seeing an equal text, consumes the entry and does not record that packet as another plugin's
 * message. An entry lives {@link #TTL_NANOS}, so a packet that never arrives cannot clog the queue.
 * Registered on the main side, consumed on a Netty thread, hence the per-queue lock.
 */
final class OwnEchoes {

    static final long TTL_NANOS = 10_000_000_000L;

    private record Entry(String plain, long expiresAt) {}

    private final Map<UUID, Deque<Entry>> queues = new ConcurrentHashMap<>();

    void register(@NotNull UUID uuid, @NotNull String plain, long nowNanos) {
        Deque<Entry> queue = queues.computeIfAbsent(uuid, k -> new ArrayDeque<>());
        synchronized (queue) {
            dropExpired(queue, nowNanos);
            queue.addLast(new Entry(plain, nowNanos + TTL_NANOS));
        }
    }

    /** True, and the entry is removed, when {@code plain} was registered and has not expired. */
    boolean consume(@NotNull UUID uuid, @NotNull String plain, long nowNanos) {
        Deque<Entry> queue = queues.get(uuid);
        if (queue == null) return false;
        synchronized (queue) {
            dropExpired(queue, nowNanos);
            for (Iterator<Entry> it = queue.iterator(); it.hasNext(); ) {
                if (it.next().plain().equals(plain)) {
                    it.remove();
                    return true;
                }
            }
        }
        return false;
    }

    void forget(@NotNull UUID uuid) {
        queues.remove(uuid);
    }

    private static void dropExpired(Deque<Entry> queue, long nowNanos) {
        // Entries are added in time order, so the expired ones are at the head.
        while (!queue.isEmpty() && queue.peekFirst().expiresAt() - nowNanos < 0) queue.pollFirst();
    }
}
