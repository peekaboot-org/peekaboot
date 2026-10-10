package org.peekaboot.testingapp;

/** PostgreSQL functions the app's SQL calls, registered on the test profile's H2 by h2-functions.sql. */
public final class H2Functions {

    private H2Functions() {}

    /** PostgreSQL's pg_sleep, so a query that waits on purpose waits on H2 too. */
    public static void pgSleep(double seconds) throws InterruptedException {
        Thread.sleep(Math.round(seconds * 1000));
    }
}
