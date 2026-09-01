package org.peekaboot.backend.insights;

/**
 * The boundary-aligned schedule the collector's level threads and the snapshot writer
 * share: work happens at multiples of an interval, so every sample carries a timestamp
 * that is a multiple of its level's interval and the file is written on the same grid.
 */
final class IntervalBoundary {

    private IntervalBoundary() {}

    /**
     * Sleeps until {@code offsetMs} past the next multiple of {@code intervalMs} and returns
     * that multiple - the instant the caller's work is scheduled for, fixed before the sleep
     * so a late wake-up does not shift it.
     */
    static long sleepUntilNext(long intervalMs, long offsetMs) throws InterruptedException {
        long now = System.currentTimeMillis();
        long boundary = ((now / intervalMs) + 1) * intervalMs;
        Thread.sleep(boundary + offsetMs - now);
        return boundary;
    }
}
