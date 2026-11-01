package org.peekaboot.backend.domain.trace;

import org.jspecify.annotations.Nullable;

/**
 * One fact behind a trace's outcome or its Slow listing.
 *
 * @param spanId the span the fact is about; null for a slow trace with no root in hand
 * @param detail one plain sentence, or an ERROR log's masked message
 */
public record TraceReason(
        Kind kind, @Nullable String spanId, @Nullable String detail) {

    public enum Kind {
        EXCEPTION(true),
        SERVER_ERROR(true),
        ERROR_LOG(true),
        SLOW(false);

        private final boolean failure;

        Kind(boolean failure) {
            this.failure = failure;
        }

        /** A slow trace has not failed. */
        public boolean failure() {
            return failure;
        }
    }
}
