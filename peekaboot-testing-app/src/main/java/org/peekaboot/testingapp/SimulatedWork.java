package org.peekaboot.testingapp;

/** Stands in for work this demo does not actually do. */
public final class SimulatedWork {

    private SimulatedWork() {}

    public static void pause(long millis) {

        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while simulating work", e);
        }
    }
}
