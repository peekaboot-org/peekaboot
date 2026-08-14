package org.peekaboot.backend.domain.runtime;

public record StorageInfo(
    String path,
    long total,
    long free,
    double usedPercent
) {
    public static StorageInfo of(String path, long total, long free) {
        long used = total - free;
        double percent = total > 0 ? (double) used / total * 100.0 : 0.0;
        return new StorageInfo(path, total, free, Math.round(percent * 100.0) / 100.0);
    }
}
