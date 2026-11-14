package org.peekaboot.backend.storage;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps a store that cannot write from warning on every attempt: one warning per outage,
 * where an outage runs from a failed write to the next one that succeeds. Thread-safe.
 */
public final class WriteOutage {

    private final AtomicBoolean ongoing = new AtomicBoolean();

    /** Runs {@code warn} if this write is the first to fail since the last one that succeeded. */
    public void failed(Runnable warn) {
        if (ongoing.compareAndSet(false, true)) {
            warn.run();
        }
    }

    public void succeeded() {
        ongoing.set(false);
    }
}
