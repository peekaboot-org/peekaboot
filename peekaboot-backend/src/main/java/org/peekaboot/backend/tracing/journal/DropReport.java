package org.peekaboot.backend.tracing.journal;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/** Counts the inputs the journal dropped and says so at most once a minute, so a flood costs one log line a minute. */
final class DropReport {

    private static final Duration EVERY = Duration.ofMinutes(1);

    private final AtomicLong dropped = new AtomicLong();

    /** Read and written on the writer thread only. */
    @Nullable
    private Instant lastReport;

    void count() {
        dropped.incrementAndGet();
    }

    /** The drops since the last report, and resets them; 0 where none happened or the last report is less than a minute old. */
    long due(Instant now) {
        if (dropped.get() == 0 || (lastReport != null && now.isBefore(lastReport.plus(EVERY)))) {
            return 0;
        }
        lastReport = now;
        return dropped.getAndSet(0);
    }
}
