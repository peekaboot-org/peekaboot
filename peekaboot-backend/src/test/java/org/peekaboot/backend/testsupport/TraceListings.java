package org.peekaboot.backend.testsupport;

import org.peekaboot.backend.domain.trace.TraceListParameters;

/** Builds {@link TraceListParameters} the way a request sends them: every parameter absent unless a test names it. */
public final class TraceListings {

    private TraceListings() {}

    public static Builder listing() {
        return new Builder();
    }

    public static final class Builder {

        private String limit;
        private String cursor;
        private String bucket;
        private String rootActionType;
        private String rootOperation;
        private String route;
        private String path;
        private String statusCode;
        private String outcome;
        private String minDurationMs;
        private String since;
        private String until;
        private String view;

        private Builder() {}

        public Builder limit(int limit) {
            return limit(String.valueOf(limit));
        }

        public Builder limit(String limit) {
            this.limit = limit;
            return this;
        }

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder bucket(String bucket) {
            this.bucket = bucket;
            return this;
        }

        public Builder rootActionType(String rootActionType) {
            this.rootActionType = rootActionType;
            return this;
        }

        public Builder rootOperation(String rootOperation) {
            this.rootOperation = rootOperation;
            return this;
        }

        public Builder route(String route) {
            this.route = route;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder statusCode(String statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        public Builder outcome(String outcome) {
            this.outcome = outcome;
            return this;
        }

        public Builder minDurationMs(String minDurationMs) {
            this.minDurationMs = minDurationMs;
            return this;
        }

        public Builder since(String since) {
            this.since = since;
            return this;
        }

        public Builder until(String until) {
            this.until = until;
            return this;
        }

        public Builder view(String view) {
            this.view = view;
            return this;
        }

        public TraceListParameters build() {
            return new TraceListParameters(
                    limit,
                    cursor,
                    bucket,
                    rootActionType,
                    rootOperation,
                    route,
                    path,
                    statusCode,
                    outcome,
                    minDurationMs,
                    since,
                    until,
                    view);
        }
    }
}
