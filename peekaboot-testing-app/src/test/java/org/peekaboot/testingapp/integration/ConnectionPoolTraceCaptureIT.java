package org.peekaboot.testingapp.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.stream.Collectors;
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
 * CONNECTION_POOL, so the Traces tab can keep such maintenance noise out of its default
 * view while still offering it behind the type's own filter chip.
 */
@SpringBootTest(classes = TestingApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ConnectionPoolTraceCaptureIT {

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

        // the include-list the dashboard sends by default keeps the trace out of the list
        JsonNode defaultView =
                new PeekabootApi(port).getJson("/peekaboot/api/traces/insights?rootActionType=" + defaultIncludeList());
        for (JsonNode listed : defaultView.path("traces")) {
            assertThat(listed.path("rootActionType").asString("")).isNotEqualTo(RootActionType.CONNECTION_POOL.name());
        }
    }

    /** Every type except CONNECTION_POOL - the request shape traces.js sends with no chip selected. */
    private static String defaultIncludeList() {
        return Arrays.stream(RootActionType.values())
                .filter(type -> type != RootActionType.CONNECTION_POOL)
                .map(Enum::name)
                .collect(Collectors.joining(","));
    }
}
