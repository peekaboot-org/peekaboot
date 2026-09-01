package org.peekaboot.backend.domain.runtime;

/** Heap and non-heap usage, with the heap percentage computed here. */
public record MemoryInfo(long heapUsed, long heapMax, double heapUsedPercent, long nonHeapUsed) {
    public static MemoryInfo of(long heapUsed, long heapMax, long nonHeapUsed) {
        double percent = heapMax > 0 ? (double) heapUsed / heapMax * 100.0 : 0.0;
        return new MemoryInfo(heapUsed, heapMax, Math.round(percent * 100.0) / 100.0, nonHeapUsed);
    }
}
