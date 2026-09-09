package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.management.OperatingSystemMXBean;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.domain.runtime.MachineInfo.Signals;

class MachineInfoTest {

    private static final long SIXTEEN_GIB = 16L * 1024 * 1024 * 1024;
    private static final NetworkAddress.Signals NO_NETWORK =
            new NetworkAddress.Signals(List::of, InetAddress::getHostAddress, Duration.ofSeconds(1));

    /** A HotSpot-style platform bean reporting {@code totalMemory}; the JDK interface is the only seam it offers. */
    private static OperatingSystemMXBean hotspotBean(long totalMemory) {
        com.sun.management.OperatingSystemMXBean bean = mock(com.sun.management.OperatingSystemMXBean.class);
        when(bean.getTotalMemorySize()).thenReturn(totalMemory);
        return bean;
    }

    private static Signals signals(Path cpuinfo, OperatingSystemMXBean os) {
        return new Signals(cpuinfo, 8, os, 512L * 1024 * 1024, ContainerRuntime.DOCKER, NO_NETWORK);
    }

    /** Two processors on one physical core each, in the kernel's key\t: value shape. */
    private static Path cpuinfo(Path dir) throws Exception {
        return Files.writeString(dir.resolve("cpuinfo"), """
                processor\t: 0
                model name\t: Test CPU
                physical id\t: 0
                core id\t: 0

                processor\t: 1
                model name\t: Test CPU
                physical id\t: 0
                core id\t: 1

                """);
    }

    @Test
    void currentIsComputedOnceAndCached() {
        // machine facts are static for the JVM's lifetime; current() is called on
        // every insights request and must not re-read /proc/cpuinfo each time
        assertThat(MachineInfo.current()).isSameAs(MachineInfo.current());
    }

    @Test
    void theCpuModelAndTopologyComeFromTheCpuinfoFile(@TempDir Path dir) throws Exception {
        MachineInfo info = MachineInfo.read(signals(cpuinfo(dir), hotspotBean(SIXTEEN_GIB)));

        assertThat(info.cpuModel()).isEqualTo("Test CPU");
        assertThat(info.cpuTopology()).isEqualTo(new CpuTopology(2, 1));
    }

    /** Off Linux there is no file; the model and topology are simply unknown, never an error. */
    @Test
    void aMissingCpuinfoLeavesTheModelAndTopologyUnknown(@TempDir Path dir) {
        MachineInfo info = MachineInfo.read(signals(dir.resolve("cpuinfo"), hotspotBean(SIXTEEN_GIB)));

        assertThat(info.cpuModel()).isNull();
        assertThat(info.cpuTopology()).isNull();
    }

    @Test
    void totalMemoryIsWhatTheHotspotBeanReports(@TempDir Path dir) throws Exception {
        assertThat(MachineInfo.read(signals(cpuinfo(dir), hotspotBean(SIXTEEN_GIB)))
                        .totalMemory())
                .isEqualTo(SIXTEEN_GIB);
    }

    @Test
    void totalMemoryIsUnknownWhenTheHotspotBeanReportsNone(@TempDir Path dir) throws Exception {
        assertThat(MachineInfo.read(signals(cpuinfo(dir), hotspotBean(0))).totalMemory())
                .isNull();
    }

    @Test
    void totalMemoryIsUnknownOnAJvmWithoutTheHotspotBean(@TempDir Path dir) throws Exception {
        OperatingSystemMXBean plainBean = mock(OperatingSystemMXBean.class);

        assertThat(MachineInfo.read(signals(cpuinfo(dir), plainBean)).totalMemory())
                .isNull();
    }

    @Test
    void theProcessorCountHeapContainerAndAddressesAreCarriedThrough(@TempDir Path dir) throws Exception {
        List<NetworkAddress.Nic> nics =
                List.of(new NetworkAddress.Nic(true, List.of(InetAddress.getByName("10.1.2.3"))));
        NetworkAddress.Signals oneNic =
                new NetworkAddress.Signals(() -> nics, address -> "db-host", Duration.ofSeconds(1));
        Signals signals = new Signals(
                cpuinfo(dir), 8, hotspotBean(SIXTEEN_GIB), 512L * 1024 * 1024, ContainerRuntime.PODMAN, oneNic);

        MachineInfo info = MachineInfo.read(signals);

        assertThat(info.cpuCount()).isEqualTo(8);
        assertThat(info.maxHeap()).isEqualTo(512L * 1024 * 1024);
        assertThat(info.container()).isEqualTo(ContainerRuntime.PODMAN);
        assertThat(info.networkAddresses()).containsExactly(new NetworkAddress("10.1.2.3", "db-host"));
    }
}
