package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ProcessInfo(String username, String uid, String gid, long pid, List<ParentProcess> parentProcesses) {

    public record ParentProcess(long pid, String command) {}

    private static final Path PROC_SELF_STATUS = Path.of("/proc/self/status");

    /**
     * Lazily computed once: the values are static for the JVM's lifetime and
     * computing them reads {@code /proc} and walks the parent process chain.
     */
    private static final class CurrentHolder {
        private static final ProcessInfo CURRENT = compute();
    }

    public static ProcessInfo current() {
        return CurrentHolder.CURRENT;
    }

    private static ProcessInfo compute() {
        String username = System.getProperty("user.name");
        long pid = ProcessHandle.current().pid();
        String uid = procStatusId(PROC_SELF_STATUS, "Uid");
        String gid = procStatusId(PROC_SELF_STATUS, "Gid");
        List<ParentProcess> parents = resolveParentProcesses();
        return new ProcessInfo(username, uid, gid, pid, parents);
    }

    private static List<ParentProcess> resolveParentProcesses() {
        List<ParentProcess> parents = new ArrayList<>();
        Optional<ProcessHandle> current = ProcessHandle.current().parent();
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
                    String[] ids = line.substring(key.length() + 1).trim().split("\\s+");
                    return ids[0].isEmpty() ? null : ids[0];
                }
            }
            return null;
        } catch (IOException e) {
            return null;
        }
    }
}
