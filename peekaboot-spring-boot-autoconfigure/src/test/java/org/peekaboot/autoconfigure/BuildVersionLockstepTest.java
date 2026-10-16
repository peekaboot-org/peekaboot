package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.autoconfigure.BuildFiles.pom;
import static org.peekaboot.autoconfigure.BuildFiles.reactorRoot;

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
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.SpringBootVersion;

/**
 * Build metadata both build systems need is declared once, in the poms, because
 * {@code maven-release-plugin} rewrites the poms on every release and knows nothing about the
 * Gradle build. A second copy on the Gradle side goes stale the moment a release runs, and
 * nothing notices, because each build is internally consistent and CI builds Gradle only on
 * Dependabot branches.
 *
 * <p>Every value a Gradle script cannot read out of a pom is written on both sides instead:
 * the Spring Boot line, the static-analysis tool versions, the coverage floors, the library
 * versions both builds declare, the frontend gate's Node version and install command, the
 * compiler release level and the IT thread count. Dependabot bumps the two ecosystems in
 * separate pull requests, so any of them can move on one side alone. Each is guarded here by
 * comparing the two declarations, which fails the Maven build every branch runs.
 *
 * <p>The testing-app pom does not inherit {@code peekaboot-parent}, so it repeats the build
 * instant, the JaCoCo, PMD and Surefire versions, the compiler release level and the Spring Boot
 * line by hand. The build instant, JaCoCo, PMD and Boot copies are compared against the root pom
 * the same way. The rest is not: Dependabot bumps both poms in one pull request.
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
    void testingAppPinsTheSamePmdVersionAsTheRootPom() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/pmd.version",
                "/project/properties/pmd.version",
                "both poms run the one PMD ruleset; a different release can flag different code");
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

    private String pomValue(Path file, String expression) throws IOException {
        try {
            String value = XPathFactory.newInstance().newXPath().evaluate(expression, pom(file));
            assertThat(value)
                    .as("%s in %s", expression, reactorRoot().relativize(file))
                    .isNotBlank();
            return value.strip();
        } catch (XPathExpressionException e) {
            throw new IllegalArgumentException(expression, e);
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
    void gradleSpellsOutTheSameValueAsMaven(Declaration maven, Declaration gradle) {
        assertThat(gradle.value()).isEqualTo(maven.value());
    }

    /**
     * Maven names the plugin, whose version is the SpotBugs release it bundles plus a patch
     * segment of its own; Gradle names the analyser.
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

    /**
     * Maven names spotless-maven-plugin and Gradle names spotless-plugin-gradle. Spotless
     * releases both plugins with spotless-lib, each under a major of its own and with the lib's
     * minor and patch: 3.10.4 and 8.10.4 both bundle lib 4.10.4. The two builds format alike
     * when everything past the major agrees.
     */
    @Test
    @Timeout(10)
    void bothBuildsRunTheSameSpotlessLib() {
        String mavenPlugin = declaredValue(
                reactorRoot().resolve("pom.xml"),
                "<artifactId>spotless-maven-plugin</artifactId>\\s*<version>([^<]+)</version>");
        String gradlePlugin =
                declaredValue(reactorRoot().resolve("buildSrc/build.gradle.kts"), "spotless-plugin-gradle:([^\"]+)\"");

        assertThat(withoutMajor(gradlePlugin))
                .as(
                        "spotless-maven-plugin %s and spotless-plugin-gradle %s must bundle the same spotless-lib",
                        mavenPlugin, gradlePlugin)
                .isEqualTo(withoutMajor(mavenPlugin));
    }

    private static String withoutMajor(String version) {
        return version.replaceFirst("^\\d+\\.", "");
    }

    static Stream<Arguments> valuesBothBuildsSpellOut() {
        Path root = reactorRoot();
        Path pom = root.resolve("pom.xml");
        Path conventions = root.resolve("buildSrc/src/main/kotlin/peekaboot.java-conventions.gradle.kts");
        Path coveragePom = root.resolve("peekaboot-coverage/pom.xml");
        Path coverageScript = root.resolve("peekaboot-coverage/build.gradle.kts");
        Path appPom = root.resolve("peekaboot-testing-app/pom.xml");
        Path appScript = root.resolve("peekaboot-testing-app/build.gradle.kts");
        Path frontendPom = root.resolve("peekaboot-frontend/pom.xml");
        Path frontendScript = root.resolve("peekaboot-frontend/build.gradle.kts");
        Path backendPom = root.resolve("peekaboot-backend/pom.xml");
        Path backendScript = root.resolve("peekaboot-backend/build.gradle.kts");
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
                        "Checkstyle",
                        pom,
                        "<artifactId>checkstyle</artifactId>\\s*<version>([^<]+)</version>",
                        conventions,
                        "checkstyle \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "PMD",
                        pom,
                        "<pmd.version>([^<]+)</pmd.version>",
                        conventions,
                        "pmd \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "JaCoCo",
                        pom,
                        "<jacoco.version>([^<]+)</jacoco.version>",
                        conventions,
                        "jacoco \\{\\s*toolVersion = \"([^\"]+)\""),
                row(
                        "JaCoCo in the coverage module",
                        pom,
                        "<jacoco.version>([^<]+)</jacoco.version>",
                        coverageScript,
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
                        "springdoc",
                        appPom,
                        "<artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>\\s*<version>([^<]+)</version>",
                        appScript,
                        "springdoc-openapi-starter-webmvc-ui:([^\"]+)\""),
                row(
                        "datasource-proxy",
                        appPom,
                        "<artifactId>datasource-proxy-spring-boot-starter</artifactId>\\s*<version>([^<]+)</version>",
                        appScript,
                        "datasource-proxy-spring-boot-starter:([^\"]+)\""),
                row(
                        "datasource-micrometer",
                        pom,
                        "<datasource-micrometer.version>([^<]+)</datasource-micrometer.version>",
                        root.resolve("gradle.properties"),
                        "datasourceMicrometerVersion=(.+)"),
                row(
                        "jdbc-url-parser",
                        backendPom,
                        "<artifactId>jdbc-url-parser</artifactId>\\s*<version>([^<]+)</version>",
                        backendScript,
                        "jdbc-url-parser:([^\"]+)\""),
                row(
                        "cron-utils",
                        backendPom,
                        "<artifactId>cron-utils</artifactId>\\s*<version>([^<]+)</version>",
                        backendScript,
                        "cron-utils:([^\"]+)\""),
                row(
                        "the IT thread count",
                        appPom,
                        "<peekaboot.it.threads>([^<]+)</peekaboot.it.threads>",
                        root.resolve("gradle.properties"),
                        "peekaboot.it.threads=(.+)"),
                row(
                        "the pinned Node runtime",
                        frontendPom,
                        "<peekaboot.node.version>([^<]+)</peekaboot.node.version>",
                        frontendScript,
                        "node \\{[\\s\\S]*?version = \"([^\"]+)\""),
                // A Gradle side that drifted back to npm's default `install` would stay green while
                // resolving outside the lockfile, and could lint on different tool versions than CI.
                row(
                        "the npm install command",
                        frontendPom,
                        "<id>npm-ci</id>[\\s\\S]*?<arguments>([^<]+)</arguments>",
                        frontendScript,
                        "npmInstallCommand = \"([^\"]+)\""));
    }

    private static Arguments row(
            String what, Path mavenFile, String mavenPattern, Path gradleFile, String gradlePattern) {
        return Arguments.of(
                Named.of(what, new Declaration(mavenFile, mavenPattern)), new Declaration(gradleFile, gradlePattern));
    }

    /** A value one build file spells out, read when its row runs so that a miss fails that row alone. */
    record Declaration(Path file, String pattern) {

        String value() {
            return declaredValue(file, pattern);
        }
    }

    /** The first capture group of {@code pattern} in {@code file}. */
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
