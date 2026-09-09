package org.peekaboot.backend.domain.runtime;

public record StorageInfo(String path, long total, long free, double usedPercent) {
    public static StorageInfo of(String path, long total, long free) {
        return new StorageInfo(path, total, free, Percent.of(total - free, total));
    }
}
