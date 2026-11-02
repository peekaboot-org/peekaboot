package org.peekaboot.backend.tracing.store;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

/**
 * One run of the application as its traces belong to it, started when it reports ready and ended
 * once a later run begins. Each bundle keeps the run it was created in, so a trace captured during
 * startup learns the start once it is known, and an incomplete trace of an ended run can say its
 * root will never arrive. Thread-safe.
 */
final class TraceRun {

    private final AtomicReference<Instant> startedAt;
    private volatile boolean ended;

    /** {@code startedAt} null while the run has not reported ready. */
    TraceRun(@Nullable Instant startedAt) {
        this.startedAt = new AtomicReference<>(startedAt);
    }

    @Nullable
    Instant startedAt() {
        return startedAt.get();
    }

    /** Keeps the first start reported: a restored run already knows its own. */
    void markStarted(Instant at) {
        startedAt.compareAndSet(null, at);
    }

    boolean ended() {
        return ended;
    }

    void end() {
        ended = true;
    }
}
