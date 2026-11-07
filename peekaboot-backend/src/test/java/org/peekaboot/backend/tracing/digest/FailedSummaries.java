package org.peekaboot.backend.tracing.digest;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceSummary;

/**
 * Builds the summary of a failed request to {@code /orders/{id}}: started at {@link #STARTED}, an
 * {@code IllegalStateException} "out of stock" thrown at {@link #FRAME}, in no reported run, unless a
 * test says otherwise. Every field the digest does not read is neutral.
 */
final class FailedSummaries {

    static final Instant STARTED = Instant.parse("2026-11-06T10:00:00Z");
    static final String FRAME = "org.acme.Shop.buy(Shop.java:42)";

    private FailedSummaries() {}

    static Builder failed(String traceId) {
        return new Builder(traceId);
    }

    static final class Builder {

        private final String traceId;

        @Nullable
        private Instant start = STARTED;

        private String route = "/orders/{id}";
        private String type = "java.lang.IllegalStateException";
        private String message = "out of stock";
        private String frame = FRAME;
        private boolean caused = true;

        @Nullable
        private Instant run;

        private Builder(String traceId) {
            this.traceId = traceId;
        }

        /** {@code null} for a summary that names no start. */
        Builder startedAt(@Nullable Instant start) {
            this.start = start;
            return this;
        }

        Builder on(String route) {
            this.route = route;
            return this;
        }

        Builder ofType(String type) {
            this.type = type;
            return this;
        }

        Builder saying(String message) {
            this.message = message;
            return this;
        }

        Builder thrownAt(String frame) {
            this.frame = frame;
            return this;
        }

        /** A failure that left no cause, such as one the caps evicted. */
        Builder withoutCause() {
            this.caused = false;
            return this;
        }

        Builder inRun(Instant run) {
            this.run = run;
            return this;
        }

        TraceSummary build() {
            return new TraceSummary(
                    traceId,
                    start,
                    10,
                    true,
                    run,
                    RootActionType.HTTP_REQUEST,
                    "GET " + route,
                    new TraceSummary.Request("GET", route, "/orders/1", 500),
                    TraceOutcome.ERROR,
                    List.of(),
                    List.of(),
                    caused ? new TraceSummary.Cause(type, message, frame, "handler") : null,
                    List.of(),
                    List.of(),
                    new TraceSummary.Counts(
                            1, new TraceSummary.QueryCounts(0, 0), new TraceSummary.LogCounts(0, 0, 0), 0, 0),
                    null);
        }
    }
}
