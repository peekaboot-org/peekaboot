package org.peekaboot.backend.domain.runtime;

import com.fasterxml.jackson.annotation.JsonValue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The container runtime this process runs under, as far as the host lets a JVM see it:
 * Docker's {@code /.dockerenv}, Podman's {@code /run/.containerenv}, Kubernetes' service
 * environment, or - when only {@code /proc/1/cgroup} names a container runtime - a
 * generic {@link #CONTAINER}. Serialised as the lowercase word the frontend renders.
 */
public enum ContainerRuntime {
    DOCKER("docker"),
    PODMAN("podman"),
    KUBERNETES("kubernetes"),
    CONTAINER("container"),
    NONE("none");

    private static final Set<String> CGROUP_MARKERS = Set.of("docker", "kubepods", "containerd");

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
    record Signals(Path dockerEnv, Path containerEnv, Map<String, String> env, Path cgroup) {

        static Signals fromRuntime() {
            return new Signals(
                    Path.of("/.dockerenv"), Path.of("/run/.containerenv"), System.getenv(), Path.of("/proc/1/cgroup"));
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
        return cgroupNamesAContainerRuntime(signals.cgroup()) ? CONTAINER : NONE;
    }

    private static boolean cgroupNamesAContainerRuntime(Path cgroup) {
        if (!Files.isRegularFile(cgroup)) {
            return false;
        }
        try {
            String content = Files.readString(cgroup).toLowerCase(Locale.ROOT);
            for (String marker : CGROUP_MARKERS) {
                if (content.contains(marker)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
