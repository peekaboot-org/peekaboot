package org.peekaboot.backend.storage;

import java.nio.file.Path;
import java.util.Optional;
import org.peekaboot.backend.config.PeekabootProperties;

/**
 * Where Peekaboot's persisted state lives. Resolution only - creating the directory
 * and surviving an unwritable one belong to the stores, which are the only code that
 * knows whether a failure is worth a log line.
 */
public final class StorageDirectory {

    private static final String UNNAMED_APPLICATION = "application";
    private static final String FOLDER = ".peekaboot";

    private final boolean enabled;
    private final Path root;

    StorageDirectory(boolean enabled, Path root) {
        this.enabled = enabled;
        this.root = root;
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

    /** The path to a state file, or empty while storage is switched off. */
    public Optional<Path> file(String name) {
        return enabled ? Optional.of(root.resolve(name)) : Optional.empty();
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
