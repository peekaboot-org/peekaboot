package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.mapper.trace.DbSpans;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * A lookup by id through the real stack - Hibernate, datasource-proxy, datasource-micrometer,
 * the OpenTelemetry bridge and the exporter - carries the id it bound, which the statement's
 * text never shows. An unknown id is enough: Hibernate issues the query either way.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class QueryParameterCaptureIT {

    @LocalServerPort
    private int port;

    private TraceApiClient traces;

    @BeforeEach
    void connect() {
        traces = new TraceApiClient(port);
    }

    @Test
    void aLookupByIdServesTheBoundIdInsideItsStatement() {
        String traceId = traces.get("/api/person/424242");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode statement = personLookup(trace);
        assertThat(statement.path("parameters").toString()).isEqualTo("[[\"424242\"]]");
        assertThat(statement.path("formatted").asString())
                .as("Hibernate is on this app's classpath")
                .startsWith("    select");
        assertThat(SpanTree.tagValues(trace, DbSpans.PARAMETERS_TAG))
                .as("served inside the statement, not again as a tag")
                .isEmpty();
    }

    private static JsonNode personLookup(JsonNode trace) {
        for (JsonNode query : trace.path("queries")) {
            if (query.path("statement").path("text").asString("").contains("from person")) {
                return query.path("statement");
            }
        }
        throw new AssertionError("no query against person among " + trace.path("queries"));
    }
}
