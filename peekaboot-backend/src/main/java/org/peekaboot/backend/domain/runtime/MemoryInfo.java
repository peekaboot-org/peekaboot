package org.peekaboot.backend.domain.runtime;

/** Heap and non-heap usage, with the heap percentage computed here. */
public record MemoryInfo(long heapUsed, long heapMax, double heapUsedPercent, long nonHeapUsed) {
    public static MemoryInfo of(long heapUsed, long heapMax, long nonHeapUsed) {
        return new MemoryInfo(heapUsed, heapMax, Percent.of(heapUsed, heapMax), nonHeapUsed);
    }
}
