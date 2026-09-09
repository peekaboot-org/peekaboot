package org.peekaboot.backend.insights;

import java.util.function.LongSupplier;

/**
 * The boundary-aligned schedule the collector's level threads and the snapshot writer
 * share: work happens at multiples of an interval, so every sample carries a timestamp
 * that is a multiple of its level's interval and the file is written on the same grid.
 */
final class IntervalBoundary {

    /** How the boundary waits; {@code Thread::sleep} in production, a recorder in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private final LongSupplier clock;
    private final Sleeper sleeper;

    /** {@code clock} supplies epoch millis; tests fix it and swap the sleeper to pin the schedule exactly. */
    IntervalBoundary(LongSupplier clock, Sleeper sleeper) {
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** The schedule's own reading of the clock, so a caller dating things against the boundaries needs no second one. */
    long now() {
        return clock.getAsLong();
    }

    /**
     * Sleeps until {@code offsetMs} past the next multiple of {@code intervalMs} and returns
     * that multiple - the instant the caller's work is scheduled for, fixed before the sleep
     * so a late wake-up does not shift it.
     */
    long sleepUntilNext(long intervalMs, long offsetMs) throws InterruptedException {
        long now = clock.getAsLong();
        long boundary = ((now / intervalMs) + 1) * intervalMs;
        sleeper.sleep(boundary + offsetMs - now);
        return boundary;
    }
}
