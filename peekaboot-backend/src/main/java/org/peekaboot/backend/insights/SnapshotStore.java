package org.peekaboot.backend.insights;

import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The persistence side of a collector's rings: loaded in the background, written on a
 * cadence and once more at shutdown. {@link #NONE} is the store while storage is off, so
 * the service drives one store the same way whether or not a file exists.
 */
interface SnapshotStore extends InsightsCollector.SnapshotSource {

    /** Storage off: nothing to load, nothing to write, and no history ever arrives. */
    // UncommentedEmptyMethodBody: the constant's name is the documentation
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    SnapshotStore NONE = new SnapshotStore() {
        @Override
        public void beginLoad() {}

        @Override
        public void start(Supplier<InsightsSnapshot> capture, BooleanSupplier historyRestored) {}

        @Override
        public void stop() {}

        @Override
        public Optional<InsightsSnapshot> awaitSnapshot(Duration timeout) {
            return Optional.empty();
        }

        @Override
        public String startupNote() {
            return "";
        }
    };

    /** Submits the load; returns immediately, so no context refresh ever waits on a file. */
    void beginLoad();

    /**
     * Starts the periodic writer against {@code capture}, the collector's current state.
     * {@code historyRestored} reports whether that collector has taken the persisted rings
     * over, which is what decides whether its state may replace them.
     */
    void start(Supplier<InsightsSnapshot> capture, BooleanSupplier historyRestored);

    /** Stops the writer and takes the final snapshot; called after the collector has stopped. */
    void stop();

    /** What the service's start-up summary says about persistence; empty while storage is off. */
    String startupNote();
}
