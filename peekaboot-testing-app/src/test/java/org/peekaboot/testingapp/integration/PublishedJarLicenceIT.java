package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every published jar carries the licence and the notice under {@code META-INF/}. An {@code *IT}
 * because it needs the packaged jars; the originals are read from {@code ../}, the reactor root
 * seen from the module directory, as {@code BuildFiles.reactorRoot()} does.
 */
class PublishedJarLicenceIT {

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "peekaboot-backend",
                "peekaboot-frontend",
                "peekaboot-spring-boot-autoconfigure",
                "peekaboot-spring-boot-starter"
            })
    void aPublishedJarCarriesTheLicenceAndTheNotice(String artifact) throws IOException {
        assertThat(bytesOf(artifact, "META-INF/LICENSE")).isEqualTo(Files.readAllBytes(Path.of("..", "LICENSE")));
        assertThat(bytesOf(artifact, "META-INF/NOTICE")).isEqualTo(Files.readAllBytes(Path.of("..", "NOTICE")));
    }

    private static byte[] bytesOf(String artifact, String name) throws IOException {
        // Third-party jars carry files of the same name, so the artifact's own is picked by its path.
        List<URL> carried = Collections.list(
                        PublishedJarLicenceIT.class.getClassLoader().getResources(name))
                .stream()
                .filter(url -> url.toString().contains("/" + artifact + "/"))
                .toList();
        assertThat(carried).withFailMessage("%s carries no %s", artifact, name).isNotEmpty();
        assertThat(carried).as("%s in %s", name, artifact).hasSize(1);
        try (InputStream file = carried.getFirst().openStream()) {
            return file.readAllBytes();
        }
    }
}
