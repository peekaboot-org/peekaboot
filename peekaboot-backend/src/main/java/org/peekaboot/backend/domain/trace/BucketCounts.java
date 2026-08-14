package org.peekaboot.backend.domain.trace;

public record BucketCounts(int all, int errors, int slow) {

    public static BucketCounts empty() {
        return new BucketCounts(0, 0, 0);
    }
}
