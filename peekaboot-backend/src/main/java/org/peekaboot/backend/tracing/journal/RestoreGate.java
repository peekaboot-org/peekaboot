package org.peekaboot.backend.tracing.journal;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.tracing.store.TraceInput;
import org.peekaboot.backend.tracing.store.TraceStore;

/**
 * Holds live inputs back while the store is restored and applies them in arrival order once the
 * restore is done, so every restored input reaches the store before any live one. A live thread
 * takes the lock only to append; the held inputs are applied outside it, a batch at a time, so the
 * exporter or a logging thread never waits for a replay. Beyond {@code capacity} an input is dropped
 * and counted. The run's start is held all the same, since every trace of the run reads its start.
 */
final class RestoreGate {

    private final Object lock = new Object();
    private final int capacity;
    private List<TraceInput> held = new ArrayList<>();
    private int dropped;
    private volatile boolean open;

    // the opening thread's own, read on it once open() has returned
    private int failed;

    @Nullable
    private RuntimeException firstFailure;

    RestoreGate(int capacity) {
        this.capacity = capacity;
    }

    /** Applies {@code input}, or holds it while the restore runs; false where it was dropped. */
    boolean pass(TraceInput input, TraceStore store) {
        if (!open) {
            synchronized (lock) {
                if (!open) {
                    if (held.size() >= capacity && !(input instanceof TraceInput.RunStarted)) {
                        dropped++;
                        return false;
                    }
                    held.add(input);
                    return true;
                }
            }
        }
        input.applyTo(store);
        return true;
    }

    /**
     * Applies what was held, including what arrives meanwhile, then lets every input through. A held input
     * that throws is counted and skipped. The writer took it when it was held, so the throw can repeat in the
     * fold and in the next restore.
     */
    void open(TraceStore store) {
        while (true) {
            List<TraceInput> batch;
            synchronized (lock) {
                if (held.isEmpty()) {
                    open = true;
                    return;
                }
                batch = held;
                held = new ArrayList<>();
            }
            batch.forEach(input -> applyHeld(input, store));
        }
    }

    private void applyHeld(TraceInput input, TraceStore store) {
        try {
            input.applyTo(store);
        } catch (RuntimeException e) {
            failed++;
            if (firstFailure == null) {
                firstFailure = e;
            }
        }
    }

    /** The held inputs that threw as they were applied; read on the thread that opened the gate. */
    int failed() {
        return failed;
    }

    /** The first of {@link #failed}, or null where none threw. */
    @Nullable
    RuntimeException firstFailure() {
        return firstFailure;
    }

    /** The inputs refused before the gate opened; final once {@link #open} has returned. */
    int dropped() {
        synchronized (lock) {
            return dropped;
        }
    }
}
