package org.peekaboot.backend.insights;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The one-shot gate between a collector's persisted history and its first write:
 * whichever level thread calls {@link #arriveBefore} first applies the persisted
 * rings, if any - a level-1 roll-up must never land in front of the history it
 * belongs to.
 *
 * <p>Waiting happens here rather than in the collector's {@code start()} so the
 * application's own startup never pays for it; the boundary a tick was scheduled for
 * is already fixed, so a short wait does not shift any timestamp.
 *
 * <p>A source that times out on {@link #RESTORE_WAIT} or throws is abandoned for good:
 * the state leaves {@code PENDING} either way, so a failed or partial restore is never
 * retried on top of a collector that has since started ticking. A propagating exception
 * is left to the caller's own handler; losing the one tick or roll-up it was guarding is
 * harmless, since the collector pads the gap on its next write.
 */
final class SnapshotRestoreBarrier {

    private enum State {
        PENDING,
        APPLIED,
        ABANDONED
    }

    /** How long a level thread holds its first write waiting for the persisted rings. */
    private static final Duration RESTORE_WAIT = Duration.ofSeconds(5);

    private final InsightsCollector.SnapshotSource snapshotSource;
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);
    private final Object lock = new Object();

    SnapshotRestoreBarrier(InsightsCollector.SnapshotSource snapshotSource) {
        this.snapshotSource = snapshotSource;
    }

    /** Whether the persisted rings reached the collector; false while pending and after an abandoned attempt. */
    boolean hasApplied() {
        return state.get() == State.APPLIED;
    }

    /** Applies {@code restore} to the persisted snapshot, once, before returning control to the caller. */
    void arriveBefore(Consumer<InsightsSnapshot> restore) {
        if (state.get() != State.PENDING) {
            return;
        }
        synchronized (lock) {
            if (state.get() != State.PENDING) {
                return;
            }
            State outcome = State.ABANDONED;
            try {
                Optional<InsightsSnapshot> snapshot = snapshotSource.awaitSnapshot(RESTORE_WAIT);
                if (snapshot.isPresent()) {
                    restore.accept(snapshot.get());
                    outcome = State.APPLIED;
                }
            } finally {
                state.set(outcome);
            }
        }
    }
}
