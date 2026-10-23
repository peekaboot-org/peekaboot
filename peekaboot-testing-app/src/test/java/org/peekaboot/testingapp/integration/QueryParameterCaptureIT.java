package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.peekaboot.backend.mapper.trace.DbSpans;
import tools.jackson.databind.JsonNode;

/**
 * A lookup by id through the real stack - Hibernate, datasource-proxy, datasource-micrometer,
 * the OpenTelemetry bridge and the exporter - carries the id it bound, which the statement's
 * text never shows. An unknown id is enough: Hibernate issues the query either way.
 */
class QueryParameterCaptureIT extends TestingAppITBase {

    @Test
    void aLookupByIdServesTheBoundIdInsideItsStatement() {
        String traceId = traces.get("/api/person/424242");

        JsonNode trace = traces.awaitTrace(traceId, TraceApiClient.ROOT_SPAN_EXPORTED);

        JsonNode statement = TraceQueries.statementOf(trace, TraceQueries.queryContaining(trace, "from person"));
        assertThat(statement.path("parameters").toString()).isEqualTo("[[\"424242\"]]");
        assertThat(statement.path("formatted").asString())
                .as("Hibernate is on this app's classpath")
                .startsWith("    select");
        assertThat(SpanTree.tagValues(trace, DbSpans.PARAMETERS_TAG))
                .as("served inside the statement, not again as a tag")
                .isEmpty();
    }
}
