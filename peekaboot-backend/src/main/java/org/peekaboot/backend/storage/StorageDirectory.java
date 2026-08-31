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
     * {@code ${user.home}/.peekaboot/<application name>}, which survives both a reboot
     * and a {@code mvn clean} and never lands inside the application's own repository.
     */
    public static StorageDirectory resolve(PeekabootProperties.Storage storage, String applicationName) {
        String dir = storage.getDir();
        Path root = dir != null && !dir.isBlank()
                ? Path.of(dir.trim())
                : Path.of(System.getProperty("user.home"), FOLDER, folderName(applicationName));
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

    /** An application name is free-form; a folder name is not, and must not escape the parent. */
    private static String folderName(String applicationName) {
        if (applicationName == null || applicationName.isBlank()) {
            return UNNAMED_APPLICATION;
        }
        return applicationName.trim().replaceAll("[^A-Za-z0-9]", "-");
    }
}
