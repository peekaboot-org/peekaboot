package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ProcessInfo(String username, String uid, String gid, long pid, List<ParentProcess> parentProcesses) {

    public record ParentProcess(long pid, String command) {}

    /**
     * The process facts, read once ({@link #current()} caches the result: they are static
     * for the JVM's lifetime). A record so tests can state a process tree outright instead
     * of depending on the one the test runner happens to be.
     */
    record Signals(String username, ProcessHandle self, Path status) {

        static Signals fromRuntime() {
            return new Signals(System.getProperty("user.name"), ProcessHandle.current(), Path.of("/proc/self/status"));
        }
    }

    /**
     * Lazily computed once: the values are static for the JVM's lifetime and
     * computing them reads {@code /proc} and walks the parent process chain.
     */
    private static final class CurrentHolder {
        private static final ProcessInfo CURRENT = read(Signals.fromRuntime());
    }

    public static ProcessInfo current() {
        return CurrentHolder.CURRENT;
    }

    static ProcessInfo read(Signals signals) {
        String uid = procStatusId(signals.status(), "Uid");
        String gid = procStatusId(signals.status(), "Gid");
        List<ParentProcess> parents = resolveParentProcesses(signals.self());
        return new ProcessInfo(signals.username(), uid, gid, signals.self().pid(), parents);
    }

    private static List<ParentProcess> resolveParentProcesses(ProcessHandle self) {
        List<ParentProcess> parents = new ArrayList<>();
        Optional<ProcessHandle> current = self.parent();
        while (current.isPresent()) {
            ProcessHandle handle = current.get();
            String command =
                    handle.info().command().map(ProcessInfo::extractCommandName).orElse("");
            parents.add(new ParentProcess(handle.pid(), command));
            current = handle.parent();
        }
        return List.copyOf(parents);
    }

    private static String extractCommandName(String fullPath) {
        int lastSlash = fullPath.lastIndexOf('/');
        if (lastSlash < 0) {
            lastSlash = fullPath.lastIndexOf('\\');
        }
        return lastSlash >= 0 ? fullPath.substring(lastSlash + 1) : fullPath;
    }

    /**
     * The real id, first of the four on {@code status}'s {@code Uid:}/{@code Gid:} line -
     * the credentials the process runs under, which no file's owner (the working
     * directory's included) reliably shares. A plain file read, no forking. Null where
     * the file or the line is absent (anything but Linux) or unreadable.
     */
    static String procStatusId(Path status, String key) {
        try {
            for (String line : Files.readAllLines(status)) {
                if (line.startsWith(key + ":")) {
                    String[] ids = line.substring(key.length() + 1).trim().split("\\s+", -1);
                    return ids[0].isEmpty() ? null : ids[0];
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
