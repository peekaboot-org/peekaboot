package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Physical CPU layout, parsed best-effort from Linux's {@code /proc/cpuinfo} (a plain
 * file read, no forking): the number of physical cores and the SMT/hyper-threading
 * factor. Physical cores are the unique (physical id, core id) pairs; kernels that
 * expose neither fall back to {@code siblings}/{@code cpu cores}. Anywhere the file is
 * absent or doesn't add up (non-Linux, exotic kernels), the topology is simply unknown.
 */
public record CpuTopology(int physicalCores, int threadsPerCore) {

    /** The topology {@code cpuinfo} describes, or {@code null} where it can't be read coherently. */
    static CpuTopology fromCpuinfo(Path cpuinfo) {
        List<String> lines;
        try {
            lines = Files.readAllLines(cpuinfo);
        } catch (IOException e) {
            return null;
        }
        CpuinfoScan scan = new CpuinfoScan();
        lines.forEach(scan::take);
        return scan.toTopology();
    }

    /** Accumulates the topology fields of the {@code key : value} lines fed to {@link #take}. */
    private static final class CpuinfoScan {

        private int logical;
        private final Set<String> cores = new HashSet<>();
        private Integer cpuCores;
        private Integer siblings;
        private String physicalId = "0";

        void take(String line) {
            int colon = line.indexOf(':');
            if (colon < 0) {
                return;
            }
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            switch (key) {
                case "processor" -> {
                    logical++;
                    physicalId = "0";
                }
                case "physical id" -> physicalId = value;
                case "core id" -> cores.add(physicalId + "/" + value);
                case "cpu cores" -> cpuCores = parseOrNull(value);
                case "siblings" -> siblings = parseOrNull(value);
                default -> {
                    /* not a topology field */
                }
            }
        }

        CpuTopology toTopology() {
            if (logical <= 0) {
                return null;
            }
            Integer physical = physicalCores();
            if (physical == null || physical <= 0 || logical % physical != 0) {
                return null;
            }
            return new CpuTopology(physical, logical / physical);
        }

        private Integer physicalCores() {
            if (!cores.isEmpty()) {
                return cores.size();
            }
            if (cpuCores == null || cpuCores <= 0) {
                return null;
            }
            if (siblings != null && siblings > 0 && siblings % cpuCores == 0) {
                int threadsPerCore = siblings / cpuCores;
                return logical % threadsPerCore == 0 ? logical / threadsPerCore : null;
            }
            return cpuCores;
        }

        private static Integer parseOrNull(String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
