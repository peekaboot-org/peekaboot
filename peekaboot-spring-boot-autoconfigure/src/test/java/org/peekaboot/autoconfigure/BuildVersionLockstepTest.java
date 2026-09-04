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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootVersion;

/**
 * Build metadata both build systems need is declared once, in the poms, because
 * {@code maven-release-plugin} rewrites the poms on every release and knows nothing about the
 * Gradle build. A second copy on the Gradle side is not merely redundant - it goes stale the
 * moment a release runs, and nothing notices, because each build is internally consistent and
 * CI runs Maven only.
 *
 * <p>The Spring Boot version is the one value still declared on both sides, because Dependabot
 * cannot group the two ecosystems into one pull request and a bump can land on one side alone.
 * That one is guarded by comparing the values instead.
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
