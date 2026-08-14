package org.peekaboot.backend.domain.runtime;

/**
 * Rich domain record for memory information.
 *
 * Uses explicit typing because we pre-calculate heapUsedPercent
 * to move this logic from frontend to backend.
 */
public record MemoryInfo(
    long heapUsed,
    long heapMax,
    double heapUsedPercent,
    long nonHeapUsed
) {
    public static MemoryInfo of(long heapUsed, long heapMax, long nonHeapUsed) {
        double percent = heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;
        return new MemoryInfo(heapUsed, heapMax, Math.round(percent * 100.0) / 100.0, nonHeapUsed);
    }
}
