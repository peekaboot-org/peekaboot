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
     * Lazily computed once: the values are static for the JVM's lifetime and
     * computing them reads filesystem attributes and walks the parent process chain.
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
        String uid = unixAttribute("uid");
        String gid = unixAttribute("gid");
        List<ParentProcess> parents = resolveParentProcesses();
        return new ProcessInfo(username, uid, gid, pid, parents);
    }

    public static ProcessInfo of(
            String username, String uid, String gid, long pid, List<ParentProcess> parentProcesses) {
        return new ProcessInfo(username, uid, gid, pid, parentProcesses != null ? parentProcesses : List.of());
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
     * The working directory's owner id from the "unix" attribute view - the process's own
     * uid/gid without forking {@code id} from a request thread. Null where the view is
     * unsupported (Windows) or unreadable.
     */
    private static String unixAttribute(String attribute) {
        try {
            Object value = Files.getAttribute(Path.of("."), "unix:" + attribute);
            return value != null ? String.valueOf(value) : null;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            return null;
        }
    }
}
