package org.peekaboot.backend.lifecycle;

import java.util.Map;

/**
 * One thing that happened to the application: it became ready, or its context closed.
 *
 * <p>A start carries every entry of the build and git info, not just the handful the
 * dashboard renders - the file is the only record that survives the process, and a
 * property nobody reads today costs one map entry.
 */
public record LifecycleEvent(Type type, long epochMs, long pid, Map<String, String> build, Map<String, String> git) {

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
