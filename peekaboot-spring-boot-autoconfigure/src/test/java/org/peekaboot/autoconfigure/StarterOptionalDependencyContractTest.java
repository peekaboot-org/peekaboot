package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.autoconfigure.BuildFiles.pom;
import static org.peekaboot.autoconfigure.BuildFiles.reactorRoot;
import static org.peekaboot.autoconfigure.BuildFiles.xpathText;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** Holds the starter's ban list to the {@code <optional>} flags it exists to protect. */
class StarterOptionalDependencyContractTest {

    /** Optional in a published module, yet a dependency of the starter puts each on every consumer's classpath. */
    private static final Set<String> BROUGHT_BY_THE_STARTER = Set.of(
            // spring-boot-starter, through spring-boot-starter-logging
            "ch.qos.logback:logback-classic",
            // spring-boot-starter-actuator
            "org.springframework.boot:spring-boot-health",
            // spring-boot-starter-actuator
            "io.micrometer:micrometer-observation",
            // spring-boot-starter-opentelemetry, through spring-boot-micrometer-tracing-opentelemetry
            "org.springframework.boot:spring-boot-micrometer-observation",
            // spring-boot-starter-opentelemetry, through micrometer-tracing-bridge-otel
            "io.opentelemetry:opentelemetry-sdk-trace",
            // datasource-micrometer-spring-boot
            "net.ttddyy.observation:datasource-micrometer");

    private static final Pattern GRADLE_BAN_LIST = Pattern.compile("bannedFromConsumers = setOf\\(([^)]*)\\)");

    private static final Pattern QUOTED = Pattern.compile("\"([^\"]+)\"");

    @Test
    @Timeout(10)
    void everyOptionalDependencyIsBannedFromTheStarterOrBroughtByIt() throws IOException {
        Set<String> optional = new TreeSet<>(optionalDependenciesOf("peekaboot-backend"));
        optional.addAll(optionalDependenciesOf("peekaboot-spring-boot-autoconfigure"));
        Set<String> leftToTheHost = new TreeSet<>(optional);
        leftToTheHost.removeAll(BROUGHT_BY_THE_STARTER);

        assertThat(bannedByMaven())
                .as("the starter's enforcer rule bans each <optional> dependency its own dependencies do not bring")
                .containsExactlyInAnyOrderElementsOf(leftToTheHost);
        assertThat(optional)
                .as("a coordinate exempt from the ban is still <optional> in a published module")
                .containsAll(BROUGHT_BY_THE_STARTER);
    }

    @Test
    @Timeout(10)
    void gradleBansWhatMavenBans() throws IOException {
        assertThat(bannedByGradle())
                .as("checkOptionalDependencyContract mirrors the enforcer rule on the starter")
                .containsExactlyInAnyOrderElementsOf(bannedByMaven());
    }

    private static Set<String> optionalDependenciesOf(String module) throws IOException {
        return elements(pom(reactorRoot().resolve(module).resolve("pom.xml")).getElementsByTagName("dependency"))
                .filter(dependency -> xpathText(dependency, "optional").equals("true"))
                .map(dependency -> xpathText(dependency, "groupId") + ":" + xpathText(dependency, "artifactId"))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> bannedByMaven() throws IOException {
        return elements(pom(starterFile("pom.xml")).getElementsByTagName("bannedDependencies"))
                .flatMap(rule -> elements(((Element) rule).getElementsByTagName("exclude")))
                .map(exclude -> exclude.getTextContent().strip())
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Set<String> bannedByGradle() throws IOException {
        Matcher banList = GRADLE_BAN_LIST.matcher(Files.readString(starterFile("build.gradle.kts")));
        assertThat(banList.find())
                .as("the starter's Gradle script declares bannedFromConsumers")
                .isTrue();
        return QUOTED.matcher(banList.group(1))
                .results()
                .map(coordinate -> coordinate.group(1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private static Path starterFile(String name) {
        return reactorRoot().resolve("peekaboot-spring-boot-starter").resolve(name);
    }

    private static Stream<Node> elements(NodeList list) {
        return IntStream.range(0, list.getLength()).mapToObj(list::item);
    }
}
