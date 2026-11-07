package org.peekaboot.backend.domain.errors;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.digest.DigestedError;

/**
 * One failure the application's traces showed, as {@code GET /api/errors} serves it. The fields are
 * {@link DigestedError}'s.
 *
 * @param traceAvailable whether the store still holds {@code lastTraceId}, so {@code /api/traces/{id}} serves it
 */
public record ErrorDigestEntry(
        @Nullable String type,
        @Nullable String route,
        @Nullable String applicationFrame,
        int count,
        Instant firstSeen,
        Instant lastSeen,
        @Nullable String lastMessage,
        String lastTraceId,
        boolean traceAvailable,
        List<Instant> runs) {

    public static ErrorDigestEntry of(DigestedError error, boolean traceAvailable) {
        return new ErrorDigestEntry(
                error.type(),
                error.route(),
                error.applicationFrame(),
                error.count(),
                error.firstSeen(),
                error.lastSeen(),
                error.lastMessage(),
                error.lastTraceId(),
                traceAvailable,
                error.runs());
    }
}
