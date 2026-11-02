package org.peekaboot.backend.testsupport;

import java.lang.management.ManagementFactory;
import java.lang.management.MonitorInfo;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.List;

/**
 * Who holds the monitor a thread waits for, read from the JVM itself, so a test can pin an
 * interleaving by holding a lock and waiting on that fact, never on a delay. Platform threads
 * only: the thread MXBean does not report virtual threads.
 */
public final class Monitors {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Monitors() {}

    /** Whether {@code waiter} is blocked entering a monitor that {@code owner} holds. */
    public static boolean isWaitingFor(Thread waiter, Thread owner) {
        ThreadInfo info = THREADS.getThreadInfo(waiter.threadId());
        return info != null
                && info.getThreadState() == Thread.State.BLOCKED
                && info.getLockOwnerId() == owner.threadId();
    }

    /** The class names of the monitors {@code thread} holds right now. */
    public static List<String> heldBy(Thread thread) {
        ThreadInfo[] infos = THREADS.getThreadInfo(new long[] {thread.threadId()}, true, false);
        if (infos[0] == null) {
            return List.of();
        }
        return Arrays.stream(infos[0].getLockedMonitors())
                .map(MonitorInfo::getClassName)
                .toList();
    }
}
