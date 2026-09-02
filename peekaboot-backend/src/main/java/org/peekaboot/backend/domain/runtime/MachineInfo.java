package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The machine (or container) the JVM runs on: logical processor count, CPU model and
 * {@link CpuTopology}, total physical memory, the JVM's max heap, the detected
 * {@link ContainerRuntime}, and the non-local {@link NetworkAddress}es.
 *
 * <p>Inside a container with limits, the JDK itself is container-aware: {@code cpuCount}
 * and {@code totalMemory} report the container's effective CPU count and memory limit,
 * not the host's. {@code cpuModel} and {@code cpuTopology} are only cheaply readable on
 * Linux ({@code /proc/cpuinfo}, no forking) and stay {@code null} elsewhere; {@code
 * totalMemory} is {@code null} on a JVM whose platform MXBean is not
 * {@code com.sun.management}'s.
 */
public record MachineInfo(
        int cpuCount,
        String cpuModel,
        CpuTopology cpuTopology,
        Long totalMemory,
        long maxHeap,
        ContainerRuntime container,
        List<NetworkAddress> networkAddresses) {

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
                CpuTopology.fromCpuinfo(Path.of("/proc/cpuinfo")),
                readTotalMemory(),
                Runtime.getRuntime().maxMemory(),
                ContainerRuntime.current(),
                NetworkAddress.discover(NetworkAddress.Signals.fromRuntime()));
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
