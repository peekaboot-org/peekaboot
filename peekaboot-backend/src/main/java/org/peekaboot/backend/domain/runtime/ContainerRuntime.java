package org.peekaboot.backend.domain.runtime;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The container runtime this process runs under, as far as the host lets a JVM see it:
 * Docker's {@code /.dockerenv}, Podman's {@code /run/.containerenv}, Kubernetes' service
 * environment, or a generic {@link #CONTAINER} for a runtime that leaves none of them.
 * That one is detected when {@code /proc/1/cgroup} names a container runtime, when the
 * {@code container} environment variable is not blank, or when {@code /proc/1/mountinfo}
 * lists {@code /etc/hostname}, {@code /etc/hosts} or {@code /etc/resolv.conf} as a mount
 * point. Serialised as the lowercase word the frontend renders.
 */
public enum ContainerRuntime {
    DOCKER("docker"),
    PODMAN("podman"),
    KUBERNETES("kubernetes"),
    CONTAINER("container"),
    NONE("none");

    private static final Set<String> CGROUP_MARKERS = Set.of("docker", "kubepods", "containerd");

    /**
     * The files a runtime binds into its container. Runtime names would not do, since a
     * host's mountinfo lists the overlay mounts of the containers it runs.
     */
    private static final Set<String> RUNTIME_BOUND_FILES = Set.of("/etc/hostname", "/etc/hosts", "/etc/resolv.conf");

    private final String wireName;

    ContainerRuntime(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * The container markers, read from the host once ({@link #current()} caches the
     * result: they are static for the process's lifetime). A record so tests can state
     * a launch shape outright instead of faking a filesystem.
     */
    record Signals(Path dockerEnv, Path containerEnv, Map<String, String> env, Path cgroup, Path mountInfo) {

        static Signals fromRuntime() {
            return new Signals(
                    Path.of("/.dockerenv"),
                    Path.of("/run/.containerenv"),
                    System.getenv(),
                    Path.of("/proc/1/cgroup"),
                    Path.of("/proc/1/mountinfo"));
        }
    }

    /** Lazily computed once; the detection reads files and the environment. */
    private static final class CurrentHolder {
        private static final ContainerRuntime CURRENT = detect(Signals.fromRuntime());
    }

    public static ContainerRuntime current() {
        return CurrentHolder.CURRENT;
    }

    static ContainerRuntime detect(Signals signals) {
        if (signals.env().containsKey("KUBERNETES_SERVICE_HOST")) {
            return KUBERNETES;
        }
        if (Files.exists(signals.dockerEnv())) {
            return DOCKER;
        }
        if (Files.exists(signals.containerEnv())) {
            return PODMAN;
        }
        return isGenericContainer(signals) ? CONTAINER : NONE;
    }

    private static boolean isGenericContainer(Signals signals) {
        return cgroupNamesAContainerRuntime(signals.cgroup())
                || environmentNamesAContainer(signals.env())
                || mountInfoBindsARuntimeFile(signals.mountInfo());
    }

    private static boolean cgroupNamesAContainerRuntime(Path cgroup) {
        return linesOf(cgroup).stream()
                .map(line -> line.toLowerCase(Locale.ROOT))
                .anyMatch(line -> CGROUP_MARKERS.stream().anyMatch(line::contains));
    }

    private static boolean environmentNamesAContainer(Map<String, String> env) {
        String container = env.get("container");
        return container != null && !container.isBlank();
    }

    private static boolean mountInfoBindsARuntimeFile(Path mountInfo) {
        return linesOf(mountInfo).stream().anyMatch(line -> RUNTIME_BOUND_FILES.contains(mountPointOf(line)));
    }

    /** The fifth field of a mountinfo line, see proc_pid_mountinfo(5). */
    private static String mountPointOf(String line) {
        String[] fields = line.split(" ", 6);
        return fields.length > 4 ? fields[4] : "";
    }

    /** A file that is missing or cannot be read is no signal. */
    private static List<String> linesOf(Path file) {
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            return Files.readAllLines(file);
        } catch (IOException e) {
            return List.of();
        }
    }
}
