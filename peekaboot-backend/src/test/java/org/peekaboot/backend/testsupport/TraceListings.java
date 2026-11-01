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
        private String bucket;
        private String rootActionType;
        private String rootOperation;
        private String view;

        private Builder() {}

        public Builder limit(int limit) {
            return limit(String.valueOf(limit));
        }

        public Builder limit(String limit) {
            this.limit = limit;
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

        public Builder view(String view) {
            this.view = view;
            return this;
        }

        public TraceListParameters build() {
            return new TraceListParameters(limit, bucket, rootActionType, rootOperation, view);
        }
    }
}
