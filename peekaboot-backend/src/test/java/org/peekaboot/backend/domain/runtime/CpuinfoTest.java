package org.peekaboot.backend.domain.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CpuTopologyTest {

    /** One /proc/cpuinfo processor block in the kernel's key\t: value shape. */
    private static String block(int processor, int physicalId, int coreId, int cores, int siblings) {
        return "processor\t: " + processor + "\n"
                + "model name\t: Test CPU\n"
                + "physical id\t: " + physicalId + "\n"
                + "siblings\t: " + siblings + "\n"
                + "core id\t: " + coreId + "\n"
                + "cpu cores\t: " + cores + "\n\n";
    }

    @Test
    void countsUniquePhysicalCoresWhenHyperThreadingDoublesTheProcessors(@TempDir Path dir) throws Exception {
        // 4 physical cores, 2 threads each: processors 0-7 map onto core ids 0-3 twice
        StringBuilder cpuinfo = new StringBuilder();
        for (int processor = 0; processor < 8; processor++) {
            cpuinfo.append(block(processor, 0, processor % 4, 4, 8));
        }
        Path file = Files.writeString(dir.resolve("cpuinfo"), cpuinfo);

        assertThat(CpuTopology.fromCpuinfo(file)).isEqualTo(new CpuTopology(4, 2));
    }

    @Test
    void reportsOneThreadPerCoreWithoutHyperThreading(@TempDir Path dir) throws Exception {
        StringBuilder cpuinfo = new StringBuilder();
        for (int processor = 0; processor < 4; processor++) {
            cpuinfo.append(block(processor, 0, processor, 4, 4));
        }
        Path file = Files.writeString(dir.resolve("cpuinfo"), cpuinfo);

        assertThat(CpuTopology.fromCpuinfo(file)).isEqualTo(new CpuTopology(4, 1));
    }

    @Test
    void countsCoresAcrossSocketsByThePhysicalIdCoreIdPair(@TempDir Path dir) throws Exception {
        // 2 sockets x 2 cores x 2 threads: core ids repeat per socket, so only the
        // (physical id, core id) pair separates socket 0's core 0 from socket 1's
        StringBuilder cpuinfo = new StringBuilder();
        for (int processor = 0; processor < 8; processor++) {
            cpuinfo.append(block(processor, processor / 4, (processor / 2) % 2, 2, 4));
        }
        Path file = Files.writeString(dir.resolve("cpuinfo"), cpuinfo);

        assertThat(CpuTopology.fromCpuinfo(file)).isEqualTo(new CpuTopology(4, 2));
    }

    @Test
    void fallsBackToCpuCoresAndSiblingsWithoutCoreIds(@TempDir Path dir) throws Exception {
        StringBuilder cpuinfo = new StringBuilder();
        for (int processor = 0; processor < 8; processor++) {
            cpuinfo.append("processor\t: ")
                    .append(processor)
                    .append("\n")
                    .append("siblings\t: 8\n")
                    .append("cpu cores\t: 4\n\n");
        }
        Path file = Files.writeString(dir.resolve("cpuinfo"), cpuinfo);

        assertThat(CpuTopology.fromCpuinfo(file)).isEqualTo(new CpuTopology(4, 2));
    }

    @Test
    void fallsBackToCpuCoresAloneWithoutSiblings(@TempDir Path dir) throws Exception {
        StringBuilder cpuinfo = new StringBuilder();
        for (int processor = 0; processor < 8; processor++) {
            cpuinfo.append("processor\t: ").append(processor).append("\n").append("cpu cores\t: 4\n\n");
        }
        Path file = Files.writeString(dir.resolve("cpuinfo"), cpuinfo);

        assertThat(CpuTopology.fromCpuinfo(file)).isEqualTo(new CpuTopology(4, 2));
    }

    @Test
    void returnsNullWithoutAnyTopologyFields(@TempDir Path dir) throws Exception {
        // aarch64 kernels commonly list processors with no physical id/core id/cpu cores
        Path file = Files.writeString(dir.resolve("cpuinfo"), "processor\t: 0\nCPU implementer\t: 0x41\n");

        assertThat(CpuTopology.fromCpuinfo(file)).isNull();
    }

    @Test
    void returnsNullOnGarbageContent(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("cpuinfo"), "processor\t: zero\ncpu cores\t: many\n<<<###>>>\n");

        assertThat(CpuTopology.fromCpuinfo(file)).isNull();
    }

    @Test
    void returnsNullWhenTheFileIsMissing(@TempDir Path dir) {
        assertThat(CpuTopology.fromCpuinfo(dir.resolve("cpuinfo"))).isNull();
    }
}
