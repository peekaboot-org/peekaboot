package org.peekaboot.autoconfigure;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import javax.xml.xpath.XPathNodes;
import org.w3c.dom.Document;
import org.w3c.dom.NamedNodeMap;
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
        return evaluate(context, expression, String.class).strip();
    }

    /** The nodes {@code expression} selects below {@code context}, in document order. */
    static List<Node> xpathNodes(Node context, String expression) {
        List<Node> nodes = new ArrayList<>();
        evaluate(context, expression, XPathNodes.class).forEach(nodes::add);
        return nodes;
    }

    private static <T> T evaluate(Node context, String expression, Class<T> type) {
        try {
            return XPathFactory.newInstance().newXPath().evaluateExpression(expression, context, type);
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(expression, e);
        }
    }

    /**
     * The element as text, without its comments and with each whitespace run collapsed, so that
     * two copies differing in layout and commentary alone read alike.
     */
    static String normalised(Node element) {
        StringBuilder text = new StringBuilder();
        appendWithoutComments(element, text);
        return text.toString().replaceAll("\\s+", " ");
    }

    private static void appendWithoutComments(Node node, StringBuilder text) {
        switch (node.getNodeType()) {
            case Node.ELEMENT_NODE -> {
                text.append('<').append(node.getNodeName());
                NamedNodeMap attributes = node.getAttributes();
                for (int i = 0; i < attributes.getLength(); i++) {
                    Node attribute = attributes.item(i);
                    text.append(' ')
                            .append(attribute.getNodeName())
                            .append("=\"")
                            .append(attribute.getNodeValue())
                            .append('"');
                }
                text.append('>');
                for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                    appendWithoutComments(child, text);
                }
                text.append("</").append(node.getNodeName()).append('>');
            }
            case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> text.append(node.getNodeValue());
            default -> {}
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
