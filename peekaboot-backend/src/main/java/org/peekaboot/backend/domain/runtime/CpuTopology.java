package org.peekaboot.backend.domain.runtime;

/**
 * Physical CPU layout: the number of physical cores and the SMT/hyper-threading factor,
 * parsed best-effort from Linux's {@code /proc/cpuinfo} by {@link Cpuinfo}. Unknown
 * ({@code null}) anywhere the file is absent or doesn't add up.
 */
public record CpuTopology(int physicalCores, int threadsPerCore) {}
