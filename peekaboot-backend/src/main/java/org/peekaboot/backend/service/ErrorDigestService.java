package org.peekaboot.backend.service;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.errors.ErrorDigestEntry;
import org.peekaboot.backend.domain.errors.ErrorDigestParameters;
import org.peekaboot.backend.domain.errors.ErrorDigestResponse;
import org.peekaboot.backend.problem.RequestParameters;
import org.peekaboot.backend.tracing.digest.ErrorDigest;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Serves the error digest. The digest and the store are absent while tracing is off, and the digest
 * then lists nothing.
 */
public class ErrorDigestService {

    @Nullable
    private final ErrorDigest digest;

    @Nullable
    private final TraceStore traceStore;

    public ErrorDigestService(@Nullable ErrorDigest digest, @Nullable TraceStore traceStore) {
        this.digest = digest;
        this.traceStore = traceStore;
    }

    /**
     * The digest's entries, the one seen latest first: those last seen at or after {@code since} and on
     * {@code route}, where the request names them. {@code route} matches the route pattern exactly, as
     * {@code summary.request.route} names it. Every parameter is read first, so a bad one is refused even
     * while tracing is off. Each entry says whether the store still holds its latest trace.
     */
    public ErrorDigestResponse list(ErrorDigestParameters parameters) {
        Instant since = RequestParameters.instant("since", parameters.since());
        String route = RequestParameters.text(parameters.route());
        if (digest == null || traceStore == null) {
            return new ErrorDigestResponse(List.of());
        }
        return new ErrorDigestResponse(digest.entries().stream()
                .filter(entry -> since == null || !entry.lastSeen().isBefore(since))
                .filter(entry -> route == null || route.equals(entry.route()))
                .map(entry -> ErrorDigestEntry.of(
                        entry, traceStore.getTrace(entry.lastTraceId()).isPresent()))
                .toList());
    }
}
