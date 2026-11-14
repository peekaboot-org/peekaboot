package org.peekaboot.backend.lifecycle;

import java.lang.management.ManagementFactory;

/**
 * This JVM, as the lifecycle log can tell it from the processes that wrote before it.
 *
 * <p>The pid alone does not tell them apart: a container's process is always pid 1. The start
 * time does the rest, because a start this JVM recorded cannot be older than the JVM.
 */
record RunningProcess(long pid, long startedEpochMs) {

    static RunningProcess current() {
        return new RunningProcess(
                ProcessHandle.current().pid(),
                ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    /** Whether {@code start} is one this process recorded. */
    boolean recorded(LifecycleEvent start) {
        return start.pid() == pid && start.epochMs() >= startedEpochMs;
    }
}
