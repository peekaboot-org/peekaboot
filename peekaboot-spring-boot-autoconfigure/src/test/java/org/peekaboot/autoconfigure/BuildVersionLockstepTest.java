package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.autoconfigure.BuildFiles.normalised;
import static org.peekaboot.autoconfigure.BuildFiles.pom;
import static org.peekaboot.autoconfigure.BuildFiles.reactorRoot;
import static org.peekaboot.autoconfigure.BuildFiles.xpathNodes;
import static org.peekaboot.autoconfigure.BuildFiles.xpathText;

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
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.w3c.dom.Node;

/**
 * Build metadata both build systems need is declared once, in the poms, because
 * {@code maven-release-plugin} rewrites the poms on every release and knows nothing about the
 * Gradle build, and Dependabot bumps a pom alone. The Gradle build reads the module version and
 * every pom property out of the poms. A second copy on the Gradle side would go stale behind a
 * green build, because each build is internally consistent and CI builds Gradle only on
 * {@code dev} and on Dependabot branches.
 *
 * <p>A value with no pom property behind it is written on both sides instead: the
 * static-analysis tool versions spelled inside plugin configuration, the library versions both
 * builds declare inline, and the frontend gate's install command. Each is guarded here by comparing the two declarations, which fails the Maven build every branch runs.
 *
 * <p>The testing-app pom does not inherit {@code peekaboot-parent}, so it repeats the build
 * instant, the JaCoCo, PMD and Surefire versions, the Java level, the Spring Boot line, the
 * plugin pins, the Error Prone setup and the five verify-bound gates by hand. Each is compared
 * against the root pom the same way. A gate is compared as a whole plugin declaration, because a
 * copied option drifts the way a copied version does.
 */
class BuildVersionLockstepTest {

    /** An ISO-8601 instant, as {@code project.build.outputTimestamp} and the Gradle scripts spell it. */
    private static final Pattern ISO_INSTANT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z");

    /** Generated trees and the git database, which no Gradle script of ours lives in. */
    private static final Set<String> PRUNED_DIRECTORIES = Set.of("target", "build", "node_modules", ".git", ".gradle");

    private static final Path ROOT_POM = reactorRoot().resolve("pom.xml");

    private static final Path TESTING_APP_POM = reactorRoot().resolve("peekaboot-testing-app/pom.xml");

    @Test
    @Timeout(10)
    void gradlePropertiesHoldsGradleSettingsAlone() throws IOException {
        assertThat(gradleProperties().stringPropertyNames())
                .as("settings.gradle.kts reads the version and the pom properties out of the poms; a copy "
                        + "in gradle.properties goes stale at the next release:prepare or Maven-only bump")
                .allMatch(name -> name.startsWith("org.gradle."));
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
    void testingAppPinsTheSameSurefireVersionAsTheRootPom() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/surefire.version",
                "/project/properties/surefire.version",
                "Dependabot can bump the root pom's surefire.version alone; the testing-app copy moves by hand");
    }

    @Test
    @Timeout(10)
    void testingAppParentsToTheSpringBootVersionTheRootPomImports() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/spring-boot.version",
                "/project/parent/version",
                "the testing-app's spring-boot-starter-parent must be the Boot line the root pom imports");
    }

    @Test
    @Timeout(10)
    void testingAppCompilesAtTheRootPomsJavaLevel() throws IOException {
        assertTestingAppMatchesRoot(
                "/project/properties/peekaboot.java.version",
                "/project/properties/java.version",
                "the root pom compiles and runs PMD at peekaboot.java.version; "
                        + "the testing-app's java.version is its copy and moves by hand");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "maven-pmd-plugin",
                "maven-checkstyle-plugin",
                "spotbugs-maven-plugin",
                "spotless-maven-plugin",
                "jacoco-maven-plugin"
            })
    @Timeout(10)
    void testingAppDeclaresTheGateAsTheRootPomDoes(String artifactId) throws IOException {
        assertTestingAppDeclaresAsRoot(
                "/project/build/plugins/plugin[artifactId='" + artifactId + "']",
                "the testing-app repeats the root pom's gate by hand; a version, a tool pin or an option "
                        + "changed in one pom alone holds the sample app to another standard");
    }

    /**
     * The compiler plugin is not compared whole: the root pom also runs the configuration
     * processor, and the testing-app pins the plugin's version under {@code pluginManagement}.
     */
    @Test
    @Timeout(10)
    void testingAppCompilesWithTheRootPomsErrorProneSetup() throws IOException {
        String configuration = "/project/build/plugins/plugin[artifactId='maven-compiler-plugin']/configuration";
        String errorProneVersion =
                configuration + "/annotationProcessorPaths/path[artifactId='error_prone_core']/version";
        assertTestingAppDeclaresAsRoot(
                configuration + "/compilerArgs",
                "these arguments load Error Prone and fail the build on its findings; "
                        + "the testing-app copy moves by hand");
        assertTestingAppMatchesRoot(
                errorProneVersion,
                errorProneVersion,
                "another Error Prone release reports other findings; the testing-app copy moves by hand");
    }

    @Test
    @Timeout(10)
    void testingAppPinsEveryPluginItSharesWithTheRootPomAtTheSameVersion() throws IOException {
        Map<String, Set<String>> root = pinnedPluginVersions(ROOT_POM);
        Map<String, Set<String>> testingApp = pinnedPluginVersions(TESTING_APP_POM);
        root.keySet().retainAll(testingApp.keySet());
        testingApp.keySet().retainAll(root.keySet());

        assertThat(testingApp)
                .as("these hold the boot-line job on the baseline's versions; a pin dropped from the "
                        + "testing-app hands the plugin back to the older parent that job switches to")
                .containsKeys(
                        "maven-install-plugin",
                        "maven-deploy-plugin",
                        "maven-compiler-plugin",
                        "maven-dependency-plugin",
                        "maven-surefire-plugin",
                        "maven-failsafe-plugin");
        assertThat(testingApp)
                .as("one reactor runs one version of each plugin; the testing-app's pins move by hand")
                .containsExactlyInAnyOrderEntriesOf(root);
    }

    /**
     * Each row is one value with no pom property behind it, so the Gradle build spells it out
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

    private void assertTestingAppMatchesRoot(String rootExpression, String testingAppExpression, String why)
            throws IOException {
        assertThat(pomValue(TESTING_APP_POM, testingAppExpression))
                .as(why)
                .isEqualTo(pomValue(ROOT_POM, rootExpression));
    }

    private void assertTestingAppDeclaresAsRoot(String expression, String why) throws IOException {
        // java.version is the testing-app's name for the Java level; the two values are compared apart.
        assertThat(pomElement(TESTING_APP_POM, expression).replace("${java.version}", "${peekaboot.java.version}"))
                .as(why)
                .isEqualTo(pomElement(ROOT_POM, expression));
    }

    /** The one element {@code expression} selects in {@code file}, as layout-free text. */
    private String pomElement(Path file, String expression) throws IOException {
        List<Node> selected = xpathNodes(pom(file), expression);
        assertThat(selected)
                .as("%s in %s", expression, reactorRoot().relativize(file))
                .hasSize(1);
        return normalised(selected.get(0));
    }

    /** The versions {@code file} pins each plugin at, by artifactId; a plugin it gives no version is left out. */
    private Map<String, Set<String>> pinnedPluginVersions(Path file) throws IOException {
        Map<String, Set<String>> versions = new TreeMap<>();
        for (Node plugin : xpathNodes(pom(file), "//plugin[version]")) {
            versions.computeIfAbsent(xpathText(plugin, "artifactId"), artifactId -> new TreeSet<>())
                    .add(xpathText(plugin, "version"));
        }
        return versions;
    }

    private String pomValue(Path file, String expression) throws IOException {
        String value = xpathText(pom(file), expression);
        assertThat(value)
                .as("%s in %s", expression, reactorRoot().relativize(file))
                .isNotBlank();
        return value;
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
}
