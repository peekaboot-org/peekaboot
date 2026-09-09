package org.peekaboot.backend.domain.runtime;

import java.lang.management.ManagementFactory;
import java.lang.management.OperatingSystemMXBean;
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

    /**
     * The host facts, read once ({@link #current()} caches the result: they are static for
     * the JVM's lifetime). A record so tests can state a machine outright instead of running
     * on one.
     */
    record Signals(
            Path cpuinfo,
            int cpuCount,
            OperatingSystemMXBean os,
            long maxHeap,
            ContainerRuntime container,
            NetworkAddress.Signals network) {

        static Signals fromRuntime() {
            Runtime runtime = Runtime.getRuntime();
            return new Signals(
                    Path.of("/proc/cpuinfo"),
                    runtime.availableProcessors(),
                    ManagementFactory.getOperatingSystemMXBean(),
                    runtime.maxMemory(),
                    ContainerRuntime.current(),
                    NetworkAddress.Signals.fromRuntime());
        }
    }

    /** Lazily computed once: the values are static for the JVM's lifetime. */
    private static final class CurrentHolder {
        private static final MachineInfo CURRENT = read(Signals.fromRuntime());
    }

    public static MachineInfo current() {
        return CurrentHolder.CURRENT;
    }

    static MachineInfo read(Signals signals) {
        Cpuinfo cpuinfo = Cpuinfo.read(signals.cpuinfo());
        return new MachineInfo(
                signals.cpuCount(),
                cpuinfo.model(),
                cpuinfo.topology(),
                readTotalMemory(signals.os()),
                signals.maxHeap(),
                signals.container(),
                NetworkAddress.discover(signals.network()));
    }

    private static Long readTotalMemory(OperatingSystemMXBean os) {
        if (os instanceof com.sun.management.OperatingSystemMXBean bean) {
            long total = bean.getTotalMemorySize();
            return total > 0 ? total : null;
        }
        return null;
    }
}
