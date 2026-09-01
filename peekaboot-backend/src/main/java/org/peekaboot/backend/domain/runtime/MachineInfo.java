package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The machine (or container) the JVM runs on: logical processor count and CPU model,
 * total physical memory, the JVM's max heap, and the detected {@link ContainerRuntime}.
 *
 * <p>Inside a container with limits, the JDK itself is container-aware: {@code cpuCount}
 * and {@code totalMemory} report the container's effective CPU count and memory limit,
 * not the host's. {@code cpuModel} is only cheaply readable on Linux ({@code /proc/cpuinfo},
 * no forking) and stays {@code null} elsewhere; {@code totalMemory} is {@code null} on a
 * JVM whose platform MXBean is not {@code com.sun.management}'s.
 */
public record MachineInfo(int cpuCount, String cpuModel, Long totalMemory, long maxHeap, ContainerRuntime container) {

    /** Lazily computed once: the values are static for the JVM's lifetime. */
    private static final class CurrentHolder {
        private static final MachineInfo CURRENT = compute();
    }

    public static MachineInfo current() {
        return CurrentHolder.CURRENT;
    }

    private static MachineInfo compute() {
        return new MachineInfo(
                Runtime.getRuntime().availableProcessors(),
                readCpuModel(Path.of("/proc/cpuinfo")),
                readTotalMemory(),
                Runtime.getRuntime().maxMemory(),
                ContainerRuntime.current());
    }

    /** The first {@code model name} line, or {@code null} where the file or line is absent. */
    static String readCpuModel(Path cpuinfo) {
        try {
            for (String line : Files.readAllLines(cpuinfo)) {
                if (line.startsWith("model name")) {
                    int colon = line.indexOf(':');
                    if (colon >= 0) {
                        return line.substring(colon + 1).trim();
                    }
                }
            }
            return null;
        } catch (IOException e) {
            // not Linux, or /proc not readable - the model is simply unknown
            return null;
        }
    }

    private static Long readTotalMemory() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
            long total = bean.getTotalMemorySize();
            return total > 0 ? total : null;
        }
        return null;
    }
}
