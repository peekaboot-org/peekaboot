package org.peekaboot.backend.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.peekaboot.backend.config.PeekabootPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A UI file the frontend jar ships, read as UTF-8 text for a server-rendered surface to inline. */
public final class ClasspathText {

    private static final Logger log = LoggerFactory.getLogger(ClasspathText.class);

    private ClasspathText() {}

    /**
     * The file served at {@code servedPath} below the base path, or empty where it cannot be
     * read. Without peekaboot-frontend on the classpath the surface has more missing than this
     * file, so a warning is all it gets.
     */
    public static Optional<String> read(String servedPath) {
        try (InputStream in = ClasspathText.class.getResourceAsStream(PeekabootPaths.CLASSPATH_ROOT + servedPath)) {
            if (in == null) {
                log.warn("{} not found on the classpath", servedPath);
                return Optional.empty();
            }
            return Optional.of(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            log.warn("Failed to read {}: {}", servedPath, e.getMessage());
            return Optional.empty();
        }
    }
}
