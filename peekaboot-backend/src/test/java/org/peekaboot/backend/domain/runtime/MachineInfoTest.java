package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

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
    void current_reportsTheCpuModelAndTopologyProcCpuinfoDescribes() {
        // on Linux this compares two reads of the same static file; elsewhere both sides
        // are null - either way the cached value matches a fresh parse
        Cpuinfo cpuinfo = Cpuinfo.read(Path.of("/proc/cpuinfo"));

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
