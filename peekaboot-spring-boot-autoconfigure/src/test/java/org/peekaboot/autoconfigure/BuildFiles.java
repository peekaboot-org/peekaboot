package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.nio.file.Path;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

/** The build's own files, for the tests that hold the build to what it declares. */
final class BuildFiles {

    private BuildFiles() {}

    /** The reactor root, reached from this module rather than from an assumed working directory. */
    static Path reactorRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    /** The stripped text {@code expression} selects below {@code context}; empty where it selects nothing. */
    static String xpathText(Node context, String expression) {
        try {
            return XPathFactory.newInstance()
                    .newXPath()
                    .evaluate(expression, context)
                    .strip();
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(expression, e);
        }
    }

    static Document pom(Path file) throws IOException {
        try {
            return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file.toFile());
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException(file + " is not a readable pom", e);
        }
    }
}
