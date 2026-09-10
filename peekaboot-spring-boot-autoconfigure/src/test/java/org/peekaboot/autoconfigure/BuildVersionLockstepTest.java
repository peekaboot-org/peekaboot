package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringBootVersion;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * Build metadata both build systems need is declared once, in the poms, because
 * {@code maven-release-plugin} rewrites the poms on every release and knows nothing about the
 * Gradle build. A second copy on the Gradle side is not merely redundant - it goes stale the
 * moment a release runs, and nothing notices, because each build is internally consistent and
 * CI runs Maven only.
 *
 * <p>Every value a Gradle script cannot read out of a pom is written on both sides instead:
 * the Spring Boot line, the tool versions behind the five gates, the coverage floors, the
 * Playwright and axe-core versions, the compiler release level and the IT thread count.
 * Dependabot bumps the two ecosystems in separate pull requests, so any of them can move on
 * one side alone. Each is guarded here by comparing the two declarations.
 *
 * <p>The testing-app pom does not inherit {@code peekaboot-parent}, so it repeats the build
 * instant, the JaCoCo version and the Spring Boot line by hand. Those copies are compared
 * against the root pom the same way.
 */
class BuildVersionLockstepTest {

    /** An ISO-8601 instant, as {@code project.build.outputTimestamp} and the Gradle scripts spell it. */
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    /** Generated trees and the git database, which no Gradle script of ours lives in. */
    private static final Set<String> PRUNED_DIRECTORIES = Set.of("target", "build", "node_modules", ".git", ".gradle");

    @Test
    @Timeout(10)
    void gradleBuildsAgainstTheSameSpringBootVersionAsMaven() throws IOException {
        assertThat(gradleProperties().getProperty("springBootVersion"))
                .as("springBootVersion in gradle.properties must match the Boot version Maven resolves")
                .isEqualTo(SpringBootVersion.getVersion());
    }

    @Test
    @Timeout(10)
    void gradleTakesTheModuleVersionFromThePomsRatherThanFromItsOwnCopy() throws IOException {
        assertThat(gradleProperties().getProperty("version"))
                .as("settings.gradle.kts reads the version from pom.xml; a copy in gradle.properties "
                        + "goes stale at the next release:prepare, which rewrites the poms alone")
                .isNull();
    }

    @Test
    @Timeout(10)
    void noGradleScriptPinsItsOwnBuildInstant() throws IOException {
        assertThat(gradleScriptsPinningAnInstant())
                .as("project.build.outputTimestamp in the poms is the only build instant; "
                        + "release:prepare rewrites it and leaves any Gradle-side copy behind")
                .isEmpty();
    }

    @Test
    @Timeout(10)
    void testingAppPinsTheSameBuildInstantAsTheRootPom() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/project.build.outputTimestamp",
                "/project/properties/project.build.outputTimestamp",
                "release:prepare rewrites the root pom's build instant; the testing-app copy moves by hand");
    }

    @Test
    @Timeout(10)
    void testingAppPinsTheSameJacocoVersionAsTheRootPom() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/jacoco.version",
                "/project/properties/jacoco.version",
                "peekaboot-coverage merges the testing-app's execution data; both agents must agree");
    }

    @Test
    @Timeout(10)
    void testingAppParentsToTheSpringBootVersionTheRootPomImports() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/spring-boot.version",
                "/project/parent/version",
                "the testing-app's spring-boot-starter-parent must be the Boot line the root pom imports");
    }

    private void assertTestingAppMatchesRoot(String rootExpression, String testingAppExpression, String why)
            throws IOException {
        assertThat(pomValue(reactorRoot().resolve("peekaboot-testing-app/pom.xml"), testingAppExpression))
                .as(why)
                .isEqualTo(pomValue(reactorRoot().resolve("pom.xml"), rootExpression));
    }

    private String pomValue(Path pom, String expression) throws IOException {
        try {
            Document document =
                    DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile());
            String value = XPathFactory.newInstance().newXPath().evaluate(expression, document);
            assertThat(value)
                    .as("%s in %s", expression, reactorRoot().relativize(pom))
                    .isNotBlank();
            return value.strip();
        } catch (ParserConfigurationException | SAXException | XPathExpressionException e) {
            throw new IllegalStateException(pom + " is not a readable pom", e);
        }
    }

    private List<String> gradleScriptsPinningAnInstant() throws IOException {
        Path root = reactorRoot();
        List<String> offenders = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return PRUNED_DIRECTORIES.contains(dir.getFileName().toString())
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.getFileName().toString().endsWith(".gradle.kts") && pinsAnInstant(file)) {
                    offenders.add(root.relativize(file).toString());
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return offenders;
    }

    private boolean pinsAnInstant(Path script) {
        try {
            return ISO_INSTANT.matcher(Files.readString(script)).find();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Each row is one value the Gradle build cannot read out of a pom, so it spells it out
     * itself. Maven is the system of record; a row fails the moment the two spellings differ.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("valuesBothBuildsSpellOut")
    @Timeout(10)
    void gradleSpellsOutTheSameValueAsMaven(String maven, String gradle) {
        assertThat(gradle).isEqualTo(maven);
    }

    /**
     * The one pair that cannot be compared literally: Maven names the plugin, whose version is
     * the SpotBugs release it bundles plus a patch segment of its own; Gradle names the analyser.
     */
    @Test
    @Timeout(10)
    void bothBuildsRunTheSameSpotBugsRelease() {
        String plugin = declaredValue(
                reactorRoot().resolve("pom.xml"),
                "<artifactId>spotbugs-maven-plugin</artifactId>\\s*<version>([^<]+)</version>");
        String analyser = declaredValue(
                reactorRoot().resolve("buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts"),
                "spotbugs \\{\\s*toolVersion = \"([^\"]+)\"");

        assertThat(plugin.replaceFirst("^(\\d+\\.\\d+\\.\\d+).*$", "$1"))
                .as("spotbugs-maven-plugin %s bundles a SpotBugs release; Gradle pins %s", plugin, analyser)
                .isEqualTo(analyser);
    }

    static Stream<Arguments> valuesBothBuildsSpellOut() {
        Path root = Path.of("..").toAbsolutePath().normalize();
        Path pom = root.resolve("pom.xml");
        Path conventions = root.resolve("buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts");
        Path coveragePom = root.resolve("peekaboot-coverage/pom.xml");
        Path coverageScript = root.resolve("peekaboot-coverage/build.gradle.kts");
        Path appPom = root.resolve("peekaboot-testing-app/pom.xml");
        Path appScript = root.resolve("peekaboot-testing-app/build.gradle.kts");
        return Stream.of(
                row(
                        "Error Prone",
                        pom,
                        "<artifactId>error_prone_core</artifactId>\\s*<version>([^<]+)</version>",
                        conventions,
                        "error_prone_core:([^\"]+)\""),
                row(
                        "palantir-java-format",
                        pom,
                        "<palantirJavaFormat>\\s*<version>([^<]+)</version>",
                        conventions,
                        "palantirJavaFormat\\(\"([^\"]+)\"\\)"),
                row(
                        "the Spotless ratchet commit",
                        pom,
                        "<spotless.ratchetFrom>([^<]+)</spotless.ratchetFrom>",
                        conventions,
                        "ratchetFrom\\(\"([^\"]+)\"\\)"),
                row(
                        "Checkstyle",
                        pom,
                        "<artifactId>checkstyle</artifactId>\\s*<version>([^<]+)</version>",
                        conventions,
                        "checkstyle \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "PMD",
                        pom,
                        "<artifactId>pmd-java</artifactId>\\s*<version>([^<]+)</version>",
                        conventions,
                        "pmd \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "JaCoCo",
                        pom,
                        "<jacoco.version>([^<]+)</jacoco.version>",
                        conventions,
                        "jacoco \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "the compiler release level",
                        pom,
                        "<peekaboot.java.version>([^<]+)</peekaboot.java.version>",
                        conventions,
                        "options.release = (\\d+)"),
                row(
                        "the line-coverage floor",
                        coveragePom,
                        "<jacoco.min.line>([^<]+)</jacoco.min.line>",
                        coverageScript,
                        "counter = \"LINE\"[\\s\\S]*?minimum = \"([^\"]+)\""),
                row(
                        "the branch-coverage floor",
                        coveragePom,
                        "<jacoco.min.branch>([^<]+)</jacoco.min.branch>",
                        coverageScript,
                        "counter = \"BRANCH\"[\\s\\S]*?minimum = \"([^\"]+)\""),
                row(
                        "Playwright",
                        appPom,
                        "<playwright.version>([^<]+)</playwright.version>",
                        appScript,
                        "playwright:playwright:([^\"]+)\""),
                row(
                        "axe-core",
                        appPom,
                        "axe-core</groupId>\\s*<artifactId>playwright</artifactId>\\s*<version>([^<]+)</version>",
                        appScript,
                        "axe-core:playwright:([^\"]+)\""),
                row(
                        "the IT thread count",
                        appPom,
                        "<peekaboot.it.threads>([^<]+)</peekaboot.it.threads>",
                        root.resolve("gradle.properties"),
                        "peekaboot.it.threads=(.+)"));
    }

    private static Arguments row(
            String what, Path mavenFile, String mavenPattern, Path gradleFile, String gradlePattern) {
        return Arguments.of(
                Named.of(what, declaredValue(mavenFile, mavenPattern)), declaredValue(gradleFile, gradlePattern));
    }

    /** The first capture group of {@code pattern} in {@code file}; a miss fails the row rather than the suite. */
    private static String declaredValue(Path file, String pattern) {
        String content;
        try {
            content = Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Matcher matcher = Pattern.compile(pattern).matcher(content);
        assertThat(matcher.find())
                .as("%s declares no value matching %s", file.getFileName(), pattern)
                .isTrue();
        return matcher.group(1).strip();
    }

    /** The reactor root, reached from this module rather than from an assumed working directory. */
    private Path reactorRoot() {
        return Path.of("..").toAbsolutePath().normalize();
    }

    private Properties gradleProperties() throws IOException {
        Path file = reactorRoot().resolve("gradle.properties");
        assertThat(file)
                .as("gradle.properties is the Gradle counterpart of the poms' versions")
                .isRegularFile();
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
        }
        return properties;
    }
}
