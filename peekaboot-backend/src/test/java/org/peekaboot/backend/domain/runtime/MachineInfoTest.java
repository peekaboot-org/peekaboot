package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    void readCpuModel_returnsTheFirstModelNameLine(@TempDir Path dir) throws Exception {
        Path cpuinfo = Files.writeString(dir.resolve("cpuinfo"), """
                processor\t: 0
                vendor_id\t: AuthenticAMD
                model name\t: AMD Ryzen 7 5800X 8-Core Processor
                processor\t: 1
                model name\t: AMD Ryzen 7 5800X 8-Core Processor
                """);

        assertThat(MachineInfo.readCpuModel(cpuinfo)).isEqualTo("AMD Ryzen 7 5800X 8-Core Processor");
    }

    @Test
    void readCpuModel_returnsNullWithoutAModelNameLine(@TempDir Path dir) throws Exception {
        // aarch64 kernels commonly expose no "model name" field at all
        Path cpuinfo = Files.writeString(dir.resolve("cpuinfo"), "processor\t: 0\nCPU implementer\t: 0x41\n");

        assertThat(MachineInfo.readCpuModel(cpuinfo)).isNull();
    }

    @Test
    void readCpuModel_returnsNullWhenTheFileIsMissing(@TempDir Path dir) {
        assertThat(MachineInfo.readCpuModel(dir.resolve("cpuinfo"))).isNull();
    }
}
