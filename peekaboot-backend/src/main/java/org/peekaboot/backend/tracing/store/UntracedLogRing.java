package org.peekaboot.backend.tracing.store;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.peekaboot.backend.tracing.event.LogCapturedEvent;

/** The newest log lines written outside any trace, oldest dropped first once {@code capacity} is reached. Thread-safe. */
final class UntracedLogRing {

    private final int capacity;
    private final Deque<LogCapturedEvent> logs = new ArrayDeque<>();

    /** {@code capacity} 0 keeps none. */
    UntracedLogRing(int capacity) {
        this.capacity = capacity;
    }

    synchronized void add(LogCapturedEvent log) {
        if (capacity == 0) {
            return;
        }
        if (logs.size() == capacity) {
            logs.removeFirst();
        }
        logs.addLast(log);
    }

    /** Oldest first. */
    synchronized List<LogCapturedEvent> logs() {
        return List.copyOf(logs);
    }
}
