package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.function.Function;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A connection acquired outside any traced work - what HikariCP's own pool maintenance
 * does - starts a trace of its own whose root is datasource-micrometer's "connection"
 * span, carrying no parent at all. Peekaboot classifies that root as
 * MAINTENANCE, so the listing endpoint keeps such noise out of the default view it
 * answers a request that names no type with, while still listing it for a request that
 * asks for the type.
 */
class PoolMaintenanceTraceCaptureIT extends TestingAppITBase {

    /** The listing request the dashboard sends with no chip selected: it names no type at all. */
    private static final String NO_TYPE_NAMED = "";

    private static final String MAINTENANCE_NAMED = "rootActionType=" + RootActionType.MAINTENANCE;

    private static final String EVERY_TYPE = "rootActionType=*";

    @Autowired
    private DataSource dataSource;

    @Test
    void aStandaloneConnectionAcquisitionBecomesAMaintenanceTrace() throws SQLException {

        // One trace listing serves every test running against this application, and HikariCP's
        // own maintenance acquires connections without any test asking. An id absent from the
        // listing before the acquisition below is the closest this test can get to naming the
        // trace it caused; matching on the type alone would assert about somebody else's.
        Set<String> listedBeforeAcquisition = Set.copyOf(traces.listedTraceIds(MAINTENANCE_NAMED));

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }

        // The inventory pool and the Liquibase endpoint's reads start such traces too; only orders-db's is ours.
        Function<JsonNode, String> poolName = listed -> listed.path("rootSpan")
                .path("tags")
                .path("jdbc.datasource.pool")
                .asString("");
        JsonNode trace = traces.awaitTraceOfType(
                RootActionType.MAINTENANCE,
                listed ->
                        !listedBeforeAcquisition.contains(listed.path("traceId").asString(""))
                                && "orders-db".equals(poolName.apply(listed)),
                poolName);

        assertThat(trace.path("rootOperation").asString("")).isEqualTo("connection");
        assertThat(trace.path("rootSpan").path("kind").asString("")).isEqualTo("CLIENT");

        // The three, on the trace this test caused: a request naming the type lists it and so
        // does the wildcard, while the default request does not. Asserting only the last would
        // hold just as well for a listing that came back empty.
        String traceId = trace.path("traceId").asString("");
        assertThat(traces.listedTraceIds(MAINTENANCE_NAMED)).contains(traceId);
        assertThat(traces.listedTraceIds(EVERY_TYPE)).contains(traceId);
        assertThat(traces.listedTraceIds(NO_TYPE_NAMED)).doesNotContain(traceId);
    }
}
