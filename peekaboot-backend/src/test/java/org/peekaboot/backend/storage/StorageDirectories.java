package org.peekaboot.backend.storage;

import java.nio.file.Path;

/** Storage directories whose writer is decided by the test, for tests outside this package. */
public final class StorageDirectories {

    private StorageDirectories() {}

    /** A switched-on directory at {@code root} that this instance writes. */
    public static StorageDirectory writer(Path root) {
        return new StorageDirectory(true, root, path -> true);
    }

    /** A switched-on directory at {@code root} that another running instance writes. */
    public static StorageDirectory secondInstance(Path root) {
        return new StorageDirectory(true, root, path -> false);
    }
}
