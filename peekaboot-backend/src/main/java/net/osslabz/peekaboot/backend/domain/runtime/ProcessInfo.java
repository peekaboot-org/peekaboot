package net.osslabz.peekaboot.backend.domain.runtime;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record ProcessInfo(
    String username,
    String uid,
    String gid,
    long pid,
    List<ParentProcess> parentProcesses
) {

    public record ParentProcess(long pid, String command) {}

    public static ProcessInfo current() {
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
        try {
            Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String result = reader.readLine();
                int exitCode = process.waitFor();
                return exitCode == 0 && result != null ? result.trim() : null;
            }
        } catch (Exception e) {
            return null;
        }
    }
}
