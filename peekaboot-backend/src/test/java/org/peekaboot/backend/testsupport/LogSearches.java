package org.peekaboot.backend.testsupport;

import org.peekaboot.backend.domain.log.LogSearchParameters;

/** Builds {@link LogSearchParameters} the way a request sends them: every parameter absent unless a test names it. */
public final class LogSearches {

    private LogSearches() {}

    public static Builder search() {
        return new Builder();
    }

    public static final class Builder {

        private String level;
        private String q;
        private String logger;
        private String traceId;
        private String since;
        private String until;
        private String limit;
        private String cursor;

        private Builder() {}

        public Builder level(String level) {
            this.level = level;
            return this;
        }

        public Builder q(String q) {
            this.q = q;
            return this;
        }

        public Builder logger(String logger) {
            this.logger = logger;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
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

        public LogSearchParameters build() {
            return new LogSearchParameters(level, q, logger, traceId, since, until, limit, cursor);
        }
    }
}
