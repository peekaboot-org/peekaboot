package org.peekaboot.backend.insights;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * The persistence side of a collector's rings: loaded in the background, written on a
 * cadence and once more at shutdown. {@link #NONE} is the store while storage is off or
 * another instance writes the directory, so the service drives one store the same way
 * whether or not a file exists.
 */
interface SnapshotStore extends InsightsCollector.SnapshotSource {

    /** No file: nothing to load, nothing to write, and no history ever arrives. */
    // UncommentedEmptyMethodBody: the constant's name is the documentation
    @SuppressWarnings("PMD.UncommentedEmptyMethodBody")
    SnapshotStore NONE = new SnapshotStore() {
        @Override
        public void beginLoad(Set<String> seriesIds) {}

        @Override
        public void start(RingCapture capture, BooleanSupplier historyRestored) {}

        @Override
        public void stop() {}

        @Override
        public Optional<InsightsSnapshot> awaitSnapshot(Duration timeout) {
            return Optional.empty();
        }

        @Override
        public boolean persists() {
            return false;
        }
    };

    /** The rings' entries dated after each level's persisted end; all of a level's entries where that end is 0. */
    @FunctionalInterface
    interface RingCapture {
        InsightsSnapshot entriesAfter(long[] persistedEndEpochMs);
    }

    /**
     * Submits the load of {@code seriesIds}' history, the series the rings hold; returns
     * immediately, so no context refresh ever waits on a file.
     */
    void beginLoad(Set<String> seriesIds);

    /**
     * Starts the periodic writer against {@code capture}, the collector's current state.
     * {@code historyRestored} reports whether that collector has taken the persisted rings
     * over, which is what decides whether its state may replace them.
     */
    void start(RingCapture capture, BooleanSupplier historyRestored);

    /** Stops the writer and takes the final snapshot; called after the collector has stopped. */
    void stop();

    /** Whether the rings outlive this run; false for {@link #NONE}. */
    boolean persists();
}
