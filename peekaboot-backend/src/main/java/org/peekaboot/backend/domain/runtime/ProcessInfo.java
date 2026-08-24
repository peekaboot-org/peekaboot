package org.peekaboot.backend.domain.runtime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public record ProcessInfo(
    String username,
    String uid,
    String gid,
    long pid,
    List<ParentProcess> parentProcesses
) {

    public record ParentProcess(long pid, String command) {}

    /**
     * Lazily computed once: the values are static for the JVM's lifetime and
     * computing them forks subprocesses and walks the parent process chain.
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
        String uid = execCommand("id", "-u");
        String gid = execCommand("id", "-g");
        List<ParentProcess> parents = resolveParentProcesses();
        return new ProcessInfo(username, uid, gid, pid, parents);
    }

    public static ProcessInfo of(String username, String uid, String gid, long pid, List<ParentProcess> parentProcesses) {
        return new ProcessInfo(username, uid, gid, pid, parentProcesses != null ? parentProcesses : List.of());
    }

    private static List<ParentProcess> resolveParentProcesses() {
        List<ParentProcess> parents = new ArrayList<>();
        Optional<ProcessHandle> current = ProcessHandle.current().parent();
        while (current.isPresent()) {
            ProcessHandle handle = current.get();
            String command = handle.info().command()
                .map(ProcessInfo::extractCommandName)
                .orElse("");
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

    private static String execCommand(String... command) {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String result = reader.readLine();
                if (!process.waitFor(2, TimeUnit.SECONDS)) {
                    return null;
                }
                return process.exitValue() == 0 && result != null ? result.trim() : null;
            }
        } catch (IOException | InterruptedException e) {
            return null;
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }
}
