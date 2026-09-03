package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.SpringBootVersion;

/**
 * The Spring Boot version is declared twice: Maven takes it from the parent pom's property,
 * Gradle from {@code springBootVersion} in gradle.properties. Dependabot cannot group the two
 * ecosystems into one pull request, so a bump can land on one side alone and leave the two
 * builds compiling against different Boot versions - which nothing else notices, because each
 * build is internally consistent and CI runs Maven only.
 */
class BuildVersionLockstepTest {

    @Test
    @Timeout(10)
    void gradleBuildsAgainstTheSameSpringBootVersionAsMaven() throws IOException {
        assertThat(gradleProperties().getProperty("springBootVersion"))
                .as("springBootVersion in gradle.properties must match the Boot version Maven resolves")
                .isEqualTo(SpringBootVersion.getVersion());
    }

    /** The reactor root, reached from this module rather than from an assumed working directory. */
    private Properties gradleProperties() throws IOException {
        Path file = Path.of("..").resolve("gradle.properties").toAbsolutePath().normalize();
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
