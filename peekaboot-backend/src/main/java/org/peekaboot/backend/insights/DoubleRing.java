package org.peekaboot.backend.insights;

/**
 * Fixed-capacity ring of primitive doubles, oldest entries overwritten first. Not thread-safe:
 * {@link SeriesRings} reaches every ring under its own lock.
 */
final class DoubleRing {

    private final double[] values;
    private int next;
    private int size;

    DoubleRing(int capacity) {
        this.values = new double[capacity];
    }

    void add(double value) {
        values[next] = value;
        next = (next + 1) % values.length;
        if (size < values.length) {
            size++;
        }
    }

    int size() {
        return size;
    }

    double[] toArray() {
        return last(size);
    }

    double[] last(int n) {
        int count = Math.min(n, size);
        double[] result = new double[count];
        int start = (next - count + values.length * 2) % values.length;
        for (int i = 0; i < count; i++) {
            result[i] = values[(start + i) % values.length];
        }
        return result;
    }

    /** Refills an empty ring from persisted values, oldest first. */
    void restore(double[] values) {
        for (double value : values) {
            add(value);
        }
    }
}
