package org.peekaboot.backend.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Marks the one process that writes a storage directory, by a lock on its {@value #FILE_NAME} file.
 *
 * <p>A lock is held until the JVM exits, so every Spring context of that JVM writes the
 * directory, also one started after the first has closed. Thread-safe.
 */
final class StorageLock {

    static final String FILE_NAME = ".lock";

    // never closed: closing any channel of a file gives up every lock this process holds on it
    private static final Map<Path, FileChannel> HELD = new HashMap<>();

    private StorageLock() {}

    /**
     * Whether this process may write {@code directory}: false only while another process
     * holds its lock. A directory that cannot be locked at all is left to its writers to report.
     */
    static synchronized boolean acquire(Path directory) {
        Path key = directory.toAbsolutePath().normalize();
        if (HELD.containsKey(key)) {
            return true;
        }
        try {
            return hold(key, OwnerOnlyFiles.openLockFile(key.resolve(FILE_NAME)));
        } catch (IOException e) {
            return true;
        }
    }

    private static boolean hold(Path key, FileChannel lockFile) {
        if (isLockedByAnotherProcess(lockFile)) {
            close(lockFile);
            return false;
        }
        HELD.put(key, lockFile);
        return true;
    }

    private static boolean isLockedByAnotherProcess(FileChannel lockFile) {
        try {
            return lockFile.tryLock() == null;
        } catch (OverlappingFileLockException | IOException e) {
            // this JVM holds the lock through another channel, or the file system has none to give
            return false;
        }
    }

    private static void close(FileChannel lockFile) {
        try {
            lockFile.close();
        } catch (IOException ignored) {
            // it held no lock
        }
    }
}
