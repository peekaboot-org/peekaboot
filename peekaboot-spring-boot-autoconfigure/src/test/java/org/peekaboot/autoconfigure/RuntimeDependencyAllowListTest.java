package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.autoconfigure.BuildFiles.pom;
import static org.peekaboot.autoconfigure.BuildFiles.reactorRoot;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The release page lists a dependency upgrade only when it changes what a release puts on a
 * user's classpath. git-cliff tells those bumps apart by an allow-list of coordinates in
 * {@code .github/release-notes/cliff.toml}, and files every other bump under build chores. A
 * published module that gains a runtime dependency the list misses would have that library's
 * upgrades hidden from users, so each one is checked here against a Dependabot subject.
 *
 * <p>A dependency declared without a version is bumped through the root pom's dependency
 * management: through its own entry there if it has one, else through the imported BOM.
 */
class RuntimeDependencyAllowListTest {

    private static final Pattern DEPENDENCY_UPGRADES_PARSER =
            Pattern.compile("\\{ message = '([^']+)', group = \"<!-- 04 -->Dependency upgrades\" }");

    private static final Set<String> RUNTIME_SCOPES = Set.of("", "compile", "runtime");

    private final XPath xpath = XPathFactory.newInstance().newXPath();

    @Test
    @Timeout(10)
    void everyRuntimeDependencyOfAPublishedModuleIsListedAsADependencyUpgrade() throws IOException {
        Pattern allowList = dependencyUpgradesAllowList();
        Set<String> coordinates = bumpedRuntimeCoordinates();

        assertThat(coordinates)
                .as("the poms' runtime dependencies resolve to the Boot BOM among others")
                .contains("org.springframework.boot:spring-boot-dependencies");
        assertThat(coordinates)
                .filteredOn(coordinate -> !allowList
                        .matcher("build(deps): bump " + coordinate + " from 1.0.0 to 1.0.1")
                        .find())
                .as("runtime dependencies cliff.toml would file under build chores; add them to its allow-list")
                .isEmpty();
    }

    private Pattern dependencyUpgradesAllowList() throws IOException {
        Matcher parser = DEPENDENCY_UPGRADES_PARSER.matcher(
                Files.readString(reactorRoot().resolve(".github/release-notes/cliff.toml")));
        assertThat(parser.find())
                .as("cliff.toml has a parser for the Dependency upgrades group")
                .isTrue();
        return Pattern.compile(parser.group(1));
    }

    /** The coordinate a Dependabot pull request names when it moves each runtime dependency. */
    private Set<String> bumpedRuntimeCoordinates() throws IOException {
        Document rootPom = pom(reactorRoot().resolve("pom.xml"));
        String reactorGroup = text(rootPom, "/project/groupId");
        List<String> importedBoms = new ArrayList<>();
        Set<String> pinnedByTheRootPom = new LinkedHashSet<>();
        for (Node dependency : nodes(rootPom, "/project/dependencyManagement/dependencies/dependency")) {
            if (text(dependency, "scope").equals("import")) {
                importedBoms.add(coordinate(dependency));
            } else {
                pinnedByTheRootPom.add(coordinate(dependency));
            }
        }

        Set<String> coordinates = new LinkedHashSet<>();
        for (Node module : nodes(rootPom, "/project/modules/module")) {
            Document modulePom =
                    pom(reactorRoot().resolve(module.getTextContent().strip()).resolve("pom.xml"));
            if (!isPublished(modulePom)) {
                continue;
            }
            for (Node dependency : nodes(modulePom, "/project/dependencies/dependency")) {
                if (text(dependency, "groupId").equals(reactorGroup)
                        || !RUNTIME_SCOPES.contains(text(dependency, "scope"))) {
                    continue;
                }
                String coordinate = coordinate(dependency);
                if (!text(dependency, "version").isEmpty() || pinnedByTheRootPom.contains(coordinate)) {
                    coordinates.add(coordinate);
                } else {
                    coordinates.addAll(importedBoms);
                }
            }
        }
        return coordinates;
    }

    /** central-publishing-maven-plugin honours skipPublishing, the deploy plugin maven.deploy.skip. */
    private boolean isPublished(Document modulePom) {
        return !text(modulePom, "/project/properties/skipPublishing").equals("true")
                && !text(modulePom, "/project/properties/maven.deploy.skip").equals("true");
    }

    private String coordinate(Node dependency) {
        return text(dependency, "groupId") + ":" + text(dependency, "artifactId");
    }

    private String text(Node context, String expression) {
        try {
            return xpath.evaluate(expression, context).strip();
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(expression, e);
        }
    }

    private List<Node> nodes(Node context, String expression) {
        try {
            NodeList list = (NodeList) xpath.evaluate(expression, context, XPathConstants.NODESET);
            List<Node> nodes = new ArrayList<>(list.getLength());
            for (int i = 0; i < list.getLength(); i++) {
                nodes.add(list.item(i));
            }
            return nodes;
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(expression, e);
        }
    }
}
