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
    void aLookupByIdCarriesTheBoundIdAsAParameterLiteral() {
        String traceId = traces.get("/api/person/424242");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        assertThat(SpanTree.tagValues(trace, DbSpans.PARAMETERS_TAG)).containsOnly("[[\"424242\"]]");
    }
}
