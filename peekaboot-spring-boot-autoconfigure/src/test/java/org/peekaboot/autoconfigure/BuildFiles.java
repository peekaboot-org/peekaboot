package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/** The build's own files, for the tests that hold the build to what it declares. */
final class BuildFiles {

    private BuildFiles() {}

    /** The reactor root, reached from this module rather than from an assumed working directory. */
    static Path reactorRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    static Document pom(Path file) throws IOException {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(file + " is not a readable pom", e);
        }
    }
}
