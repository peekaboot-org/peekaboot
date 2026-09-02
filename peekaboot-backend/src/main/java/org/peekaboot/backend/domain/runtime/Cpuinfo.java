package org.peekaboot.backend.domain.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One read of Linux's {@code /proc/cpuinfo} (a plain file read, no forking): the CPU model
 * name and the {@link CpuTopology} it describes, each {@code null} where the file does not
 * say - non-Linux, exotic kernels, or an aarch64 kernel that lists processors without a
 * model name.
 */
record Cpuinfo(String model, CpuTopology topology) {

    private static final Cpuinfo UNKNOWN = new Cpuinfo(null, null);

    static Cpuinfo read(Path cpuinfo) {
        List<String> lines;
        try {
            lines = Files.readAllLines(cpuinfo);
        } catch (IOException e) {
            return UNKNOWN;
        }
        Scan scan = new Scan();
        lines.forEach(scan::take);
        return new Cpuinfo(scan.model, scan.toTopology());
    }

    /**
     * Accumulates the fields of the {@code key : value} lines fed to {@link #take}. Physical
     * cores are the unique (physical id, core id) pairs; kernels that expose neither fall
     * back to {@code siblings}/{@code cpu cores}.
     */
    private static final class Scan {

        private String model;
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
                case "model name" -> model = model == null ? value : model;
                case "physical id" -> physicalId = value;
                case "core id" -> cores.add(physicalId + "/" + value);
                case "cpu cores" -> cpuCores = parseOrNull(value);
                case "siblings" -> siblings = parseOrNull(value);
                default -> {
                    /* not a field this cares about */
                }
            }
        }

        /** The topology the lines describe, or {@code null} where they don't add up coherently. */
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
