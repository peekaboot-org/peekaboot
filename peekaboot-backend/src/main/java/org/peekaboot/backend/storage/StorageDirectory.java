package org.peekaboot.backend.storage;

import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Predicate;
import org.peekaboot.backend.config.PeekabootProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Where Peekaboot's persisted state lives, and whether this instance is the one that
 * writes it. The first {@link #file} call while storage is on takes the directory's
 * lock, which creates the directory. Surviving an unwritable one belongs to the stores,
 * which are the only code that knows whether a failure is worth a log line.
 */
public final class StorageDirectory {

    private static final Logger log = LoggerFactory.getLogger(StorageDirectory.class);

    private static final String UNNAMED_APPLICATION = "application";
    private static final String FOLDER = ".peekaboot";

    private final boolean enabled;
    private final Path root;
    private final Predicate<Path> writerLock;
    private Boolean writer;

    StorageDirectory(boolean enabled, Path root) {
        this(enabled, root, StorageLock::acquire);
    }

    /** {@code writerLock} says whether this instance may write {@code root}; tests decide it themselves. */
    StorageDirectory(boolean enabled, Path root, Predicate<Path> writerLock) {
        this.enabled = enabled;
        this.root = root;
        this.writerLock = writerLock;
    }

    /**
     * An explicit {@code dir} is used verbatim; otherwise state lands in
     * {@code ${user.home}/.peekaboot/<application id>}, which survives both a reboot
     * and a {@code mvn clean} and never lands inside the application's own repository.
     * The id is the application's build coordinates where it has them - see
     * PeekabootStorageAutoConfiguration, which chooses it.
     */
    public static StorageDirectory resolve(PeekabootProperties.Storage storage, String applicationId) {
        String dir = storage.getDir();
        Path root = dir != null && !dir.isBlank()
                ? Path.of(dir.trim())
                : Path.of(System.getProperty("user.home"), FOLDER, folderName(applicationId));
        return new StorageDirectory(storage.isEnabled(), root);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Path root() {
        return root;
    }

    /**
     * The path to a state file, or empty while storage is switched off or another running
     * instance writes the directory. Whether this instance writes is decided on the first
     * call and holds for every later one.
     */
    public Optional<Path> file(String name) {
        return enabled && isWriter() ? Optional.of(root.resolve(name)) : Optional.empty();
    }

    private synchronized boolean isWriter() {
        if (writer == null) {
            writer = writerLock.test(root);
            if (!writer) {
                log.warn(
                        "Peekaboot: another running instance writes to {}; this instance keeps its history in"
                                + " memory. Give each instance its own peekaboot.storage.dir.",
                        root);
            }
        }
        return writer;
    }

    /**
     * An application id is free-form; a folder name is not. Anything that isn't a
     * letter, digit, dot, underscore or dash becomes a dash - collapsing a path
     * separator instead of dropping it, so {@code "../orders svc"} still can't smuggle
     * a directory boundary through. That alone doesn't stop an id of exactly "."
     * or ".." surviving intact, so those two are caught explicitly and sent to the
     * same fixed folder as no id at all.
     */
    private static String folderName(String applicationId) {
        if (applicationId == null || applicationId.isBlank()) {
            return UNNAMED_APPLICATION;
        }
        String sanitized = applicationId.trim().replaceAll("[^A-Za-z0-9._-]", "-");
        return ".".equals(sanitized) || "..".equals(sanitized) ? UNNAMED_APPLICATION : sanitized;
    }
}
