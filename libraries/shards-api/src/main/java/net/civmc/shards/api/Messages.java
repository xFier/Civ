package net.civmc.shards.api;

import java.util.Objects;

/**
 * Validation shared by the message records, so every one of them rejects the same things the same way.
 */
final class Messages {

    private Messages() {
    }

    static String requireNonBlank(final String value, final String fieldName) {
        Objects.requireNonNull(value, fieldName);
        final String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return trimmed;
    }

    static void requirePositive(final long value, final String fieldName) {
        if (value <= 0) {
            throw new IllegalArgumentException(fieldName + " must be positive");
        }
    }
}
