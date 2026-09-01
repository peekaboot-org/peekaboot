package org.peekaboot.backend.insights;

/**
 * Fixed-capacity ring of primitive doubles, oldest entries overwritten first.
 * Writers and readers are different virtual threads (collector vs. API/SSE),
 * hence the coarse synchronization - contention is a handful of ops per tick.
 */
public final class DoubleRing {

    private final double[] values;
    private int next;
    private int size;

    public DoubleRing(int capacity) {
        this.values = new double[capacity];
    }

    public synchronized void add(double value) {
        values[next] = value;
        next = (next + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return values.length;
    }

    public synchronized double[] toArray() {
        return last(size);
    }

    public synchronized double[] last(int n) {
        int count = Math.min(n, size);
        double[] result = new double[count];
        int start = (next - count + values.length * 2) % values.length;
        for (int i = 0; i < count; i++) {
            result[i] = values[(start + i) % values.length];
        }
        return result;
    }

    /** Refills an empty ring from persisted values, oldest first. */
    public synchronized void restore(double[] values) {
        for (double value : values) {
            add(value);
        }
    }
}
