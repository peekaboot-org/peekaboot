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
import org.peekaboot.testsupport.Checkout;

/**
 * Every published jar carries the licence and the notice under {@code META-INF/}. An {@code *IT}
 * because it needs the packaged jars; the originals lie in the reactor root, the directory above
 * this module that holds {@code .mvn}.
 */
class PublishedJarLicenceIT {

    private static final Path REACTOR_ROOT = Checkout.ancestorHolding(PublishedJarLicenceIT.class, ".mvn");

    @ParameterizedTest(name = "{0}")
    @ValueSource(
            strings = {
                "peekaboot-backend",
                "peekaboot-frontend",
                "peekaboot-spring-boot-autoconfigure",
                "peekaboot-spring-boot-starter"
            })
    void aPublishedJarCarriesTheLicenceAndTheNotice(String artifact) throws IOException {
        assertThat(bytesOf(artifact, "META-INF/LICENSE"))
                .isEqualTo(Files.readAllBytes(REACTOR_ROOT.resolve("LICENSE")));
        assertThat(bytesOf(artifact, "META-INF/NOTICE")).isEqualTo(Files.readAllBytes(REACTOR_ROOT.resolve("NOTICE")));
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
