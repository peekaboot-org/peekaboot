package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class MachineInfoTest {

    @Test
    void current_isComputedOnceAndCached() {
        // machine facts are static for the JVM's lifetime; current() is called on
        // every insights request and must not re-read /proc/cpuinfo each time
        assertThat(MachineInfo.current()).isSameAs(MachineInfo.current());
    }

    @Test
    void current_reportsTheLogicalProcessorCount() {
        assertThat(MachineInfo.current().cpuCount())
                .isEqualTo(Runtime.getRuntime().availableProcessors());
    }

    @Test
    void current_reportsTheJvmMaxHeap() {
        assertThat(MachineInfo.current().maxHeap())
                .isEqualTo(Runtime.getRuntime().maxMemory());
    }

    @Test
    void current_reportsTotalMemoryOnAHotspotStyleJvm() {
        // every mainstream JVM implements com.sun.management.OperatingSystemMXBean
        assertThat(MachineInfo.current().totalMemory()).isNotNull().isPositive();
    }

    @Test
    void current_reportsTheDetectedContainerRuntime() {
        assertThat(MachineInfo.current().container()).isEqualTo(ContainerRuntime.current());
    }

    @Test
    void current_takesTheCpuModelAndTopologyFromProcCpuinfoUnaltered() {
        // what Cpuinfo makes of the file is CpuinfoTest's subject; this pins that the cached
        // facts are that parse and nothing else. Off Linux there is no file and both sides
        // are null, which would compare equal without proving anything - hence the assumption.
        Cpuinfo cpuinfo = Cpuinfo.read(Path.of("/proc/cpuinfo"));
        assumeTrue(cpuinfo.model() != null, "/proc/cpuinfo describes no CPU on this platform");

        assertThat(MachineInfo.current().cpuModel()).isEqualTo(cpuinfo.model());
        assertThat(MachineInfo.current().cpuTopology()).isEqualTo(cpuinfo.topology());
    }

    @Test
    void current_reportsOnlyNonLocalNetworkAddresses() {
        assertThat(MachineInfo.current().networkAddresses())
                .isNotNull()
                .extracting(NetworkAddress::address)
                .doesNotContain("127.0.0.1", "0:0:0:0:0:0:0:1");
    }
}
