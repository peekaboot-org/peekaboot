package org.peekaboot.backend.lifecycle;

import java.util.Map;

/**
 * One thing that happened to the application: it became ready, or its context closed.
 *
 * <p>A start carries the build and git entries the lifecycle views render and no others
 * (see {@link InfoEntries}), so what the log leaves behind in a developer's home
 * directory is the handful of facts the dashboard draws with.
 */
public record LifecycleEvent(Type type, long epochMs, long pid, Map<String, String> build, Map<String, String> git) {

    /**
     * A line read back from the file may omit either map - it is a text file a person can
     * edit - and everything downstream reads them without asking first.
     */
    public LifecycleEvent {
        build = build == null ? Map.of() : build;
        git = git == null ? Map.of() : git;
    }

    public enum Type {
        START,
        STOP
    }

    public static LifecycleEvent start(long epochMs, long pid, Map<String, String> build, Map<String, String> git) {
        return new LifecycleEvent(Type.START, epochMs, pid, Map.copyOf(build), Map.copyOf(git));
    }

    /** A stop repeats no build info: it belongs to the start it follows. */
    public static LifecycleEvent stop(long epochMs, long pid) {
        return new LifecycleEvent(Type.STOP, epochMs, pid, Map.of(), Map.of());
    }
}
