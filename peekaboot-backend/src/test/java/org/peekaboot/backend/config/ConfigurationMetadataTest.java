package org.peekaboot.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.tracing.config.PeekabootTracingProperties;
import tools.jackson.databind.JsonNode;

/**
 * The configuration processor's output in this module's own classes, which an IDE reads to
 * document peekaboot.* keys. A compile that hands the processor class files instead of sources
 * writes every property without its description or default, and the jar ships that.
 */
class ConfigurationMetadataTest {

    @Test
    void everyPropertyCarriesADescription() throws IOException, URISyntaxException {
        JsonNode properties = metadata().path("properties");

        assertThat(properties.size()).as("the properties the processor found").isPositive();
        assertThat(properties
                        .valueStream()
                        .filter(property ->
                                property.path("description").asString("").isBlank())
                        .map(property -> property.path("name").asString()))
                .as("properties without a description")
                .isEmpty();
    }

    /** The processor reads a default off the field initializer, which only a source file holds. */
    @Test
    void aPropertyCarriesTheDefaultItsFieldIsInitialisedWith() throws IOException, URISyntaxException {
        JsonNode slowSpan = metadata()
                .path("properties")
                .valueStream()
                .filter(property -> "peekaboot.tracing.slow-span-threshold-ms"
                        .equals(property.path("name").asString()))
                .findFirst()
                .orElseThrow();

        assertThat(slowSpan.path("defaultValue").asLong())
                .isEqualTo(new PeekabootTracingProperties().getSlowSpanThresholdMs());
    }

    /** Read off this module's classes directory, never off the classpath, where Spring Boot's own jars carry the same name. */
    private static JsonNode metadata() throws IOException, URISyntaxException {
        Path classes = Path.of(PeekabootProperties.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
        return PeekabootJson.MAPPER.readTree(
                Files.readString(classes.resolve("META-INF/spring-configuration-metadata.json")));
    }
}
