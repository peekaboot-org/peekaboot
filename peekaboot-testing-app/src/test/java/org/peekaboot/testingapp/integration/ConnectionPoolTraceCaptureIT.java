package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.trace.RootActionType;
import org.peekaboot.testingapp.TestingApp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * A connection acquired outside any traced work - what an external health probe or
 * HikariCP's own pool maintenance does - starts a trace of its own whose root is
 * datasource-micrometer's "connection" span. Peekaboot classifies that root as
 * CONNECTION_POOL, so the listing endpoint keeps such maintenance noise out of the
 * default view it answers a request that names no type with, while still listing it for
 * a request that asks for the type - what the Traces tab's own chip sends.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConnectionPoolTraceCaptureIT {

    /** The listing request the dashboard sends with no chip selected: it names no type at all. */
    private static final String NO_TYPE_NAMED = "";

    @LocalServerPort
    private int port;

    @Autowired
    private DataSource dataSource;

    @Test
    void aStandaloneConnectionAcquisitionBecomesAConnectionPoolTrace() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.isValid(1)).isTrue();
        }

        JsonNode trace = new TraceApiClient(port).awaitTraceOfType(RootActionType.CONNECTION_POOL);

        assertThat(trace.path("rootOperation").asString("")).isEqualTo("connection");
        assertThat(trace.path("rootSpan").path("kind").asString("")).isEqualTo("CLIENT");
        assertThat(trace.path("rootSpan")
                        .path("tags")
                        .path("jdbc.datasource.pool")
                        .asString(""))
                .startsWith("HikariPool");

        // The three, on the one trace this test made: the chip's own request lists it and so
        // does the wildcard, while the default request does not. Asserting only the last would
        // hold just as well for a listing that came back empty.
        String traceId = trace.path("traceId").asString("");
        PeekabootApi api = new PeekabootApi(port);
        assertThat(listedTraceIds(api, RootActionType.CONNECTION_POOL.name())).contains(traceId);
        assertThat(listedTraceIds(api, "*")).contains(traceId);
        assertThat(listedTraceIds(api, NO_TYPE_NAMED)).doesNotContain(traceId);
    }

    /** The ids the listing endpoint returns for one {@code rootActionType} value. */
    private static List<String> listedTraceIds(PeekabootApi api, String rootActionType) {
        String typeQuery = rootActionType.isEmpty() ? "" : "&rootActionType=" + rootActionType;
        JsonNode response = api.getJson("/peekaboot/api/traces/insights?limit=10000" + typeQuery);
        List<String> ids = new ArrayList<>();
        for (JsonNode listed : response.path("traces")) {
            ids.add(listed.path("traceId").asString(""));
        }
        return ids;
    }
}
