package ru.chanti.slushaet;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Stable identifiers used for local event storage and bridge-side idempotency. */
final class MusicEventIds {
    private MusicEventIds() {}

    static String newEventId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Chanti Bridge 1.1 accepts request IDs in the form q_<64 lowercase hex chars>.
     * Hashing the immutable event ID gives the same request ID on every retry.
     */
    static String requestId(String eventId) {
        if (eventId == null || eventId.trim().isEmpty()) {
            throw new IllegalArgumentException("eventId must not be empty");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(eventId.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(64);
            for (byte value : digest) {
                out.append(String.format(Locale.US, "%02x", value & 0xff));
            }
            return "q_" + out;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
