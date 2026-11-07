package org.peekaboot.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.api.ApiDocument;
import org.peekaboot.backend.domain.trace.TraceOutcome;
import org.peekaboot.backend.domain.trace.TraceView;
import org.peekaboot.backend.tracing.store.TraceBucket;
import tools.jackson.databind.JsonNode;

/**
 * The OpenAPI document's parameter values and bounds are the ones the endpoints read. Every parameter
 * arrives as a string and is read with {@code RequestParameters}, so the enum each one names is listed here.
 */
class ApiDocumentParametersTest {

    /** By path and name, the enum each parameter that names its values is read as. */
    private static final Map<String, Class<? extends Enum<?>>> PARAMETER_ENUMS = Map.of(
            "/peekaboot/api/traces bucket", TraceBucket.class,
            "/peekaboot/api/traces outcome", TraceOutcome.class,
            "/peekaboot/api/traces view", TraceView.class,
            "/peekaboot/api/logs level", LogLevel.class,
            "/peekaboot/api/insights/data view", TraceView.class);

    private static final JsonNode DOCUMENT = ApiDocument.bundled().forServer("").content();

    @Test
    void everyParameterThatNamesItsValuesNamesTheConstantsItsEndpointReads() {
        Map<String, List<String>> documented = new LinkedHashMap<>();
        DOCUMENT.path("paths")
                .propertyStream()
                .forEach(path -> parameters(path.getValue())
                        .filter(parameter -> parameter.path("schema").has("enum"))
                        .forEach(parameter -> documented.put(
                                path.getKey() + " " + parameter.path("name").asString(),
                                parameter
                                        .path("schema")
                                        .path("enum")
                                        .valueStream()
                                        .map(JsonNode::asString)
                                        .toList())));

        assertThat(documented).containsOnlyKeys(PARAMETER_ENUMS.keySet());
        documented.forEach((parameter, values) -> assertThat(values)
                .as(parameter)
                .containsExactlyInAnyOrderElementsOf(
                        Arrays.stream(PARAMETER_ENUMS.get(parameter).getEnumConstants())
                                .map(Enum::name)
                                .toList()));
    }

    @Test
    void everyLimitIsCappedWhereTheEndpointsCapIt() {
        assertThat(DOCUMENT.path("paths")
                        .valueStream()
                        .flatMap(ApiDocumentParametersTest::parameters)
                        .filter(parameter ->
                                "limit".equals(parameter.path("name").asString()))
                        .map(limit -> limit.path("schema").path("maximum").asInt()))
                .isNotEmpty()
                .containsOnly(PageLimit.MAX);
    }

    /** An operation's parameters, each reference resolved to the parameter it names. */
    private static Stream<JsonNode> parameters(JsonNode path) {
        return path.path("get")
                .path("parameters")
                .valueStream()
                .map(parameter -> parameter.has("$ref")
                        ? DOCUMENT.at(parameter.path("$ref").asString().substring(1))
                        : parameter);
    }
}
