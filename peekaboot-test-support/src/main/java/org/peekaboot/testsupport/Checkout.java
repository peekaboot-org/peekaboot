package org.peekaboot.testsupport;

import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;

/** Files of the checkout a test runs in, found from the test's own classes. */
public final class Checkout {

    private Checkout() {}

    /** The nearest directory above {@code anchor}'s classes that holds {@code entry}. */
    public static Path ancestorHolding(Class<?> anchor, String entry) {
        URL classes = anchor.getProtectionDomain().getCodeSource().getLocation();
        try {
            return ancestorHolding(Path.of(classes.toURI()), entry);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(anchor + " was loaded from " + classes + ", which is not a valid URI", e);
        }
    }

    static Path ancestorHolding(Path start, String entry) {
        for (Path directory = start; directory != null; directory = directory.getParent()) {
            if (Files.exists(directory.resolve(entry))) {
                return directory;
            }
        }
        throw new IllegalStateException("No directory from " + start + " upwards holds " + entry);
    }
}
