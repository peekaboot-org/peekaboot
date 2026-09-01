package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.FlywayResponse;
import org.peekaboot.backend.domain.flyway.FlywayInfo;
import org.peekaboot.backend.domain.flyway.MigrationInfo;
import org.peekaboot.backend.domain.flyway.MigrationState;
import org.peekaboot.backend.testsupport.LogCapture;

class FlywayMapperTest {

    private final FlywayMapper mapper = new FlywayMapper();

    @Test
    void map_shouldExtractMigrations() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(
                                        new FlywayResponse.Migration(
                                                12345L,
                                                "Initial schema",
                                                100,
                                                "admin",
                                                "2024-01-01T10:00:00Z",
                                                1,
                                                "V1__Initial_schema.sql",
                                                "SUCCESS",
                                                "SQL",
                                                "1"),
                                        new FlywayResponse.Migration(
                                                12346L,
                                                "Add users",
                                                50,
                                                "admin",
                                                "2024-01-02T10:00:00Z",
                                                2,
                                                "V2__Add_users.sql",
                                                "SUCCESS",
                                                "SQL",
                                                "2")))),
                        null)));

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations()).hasSize(2);
        assertThat(result.migrations().get(0).version()).isEqualTo("1");
        assertThat(result.migrations().get(0).description()).isEqualTo("Initial schema");
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.SUCCESS);
    }

    /**
     * Flyway's own {@code info().all()} order is kept: versioned migrations ascending, then
     * repeatables. Sorting here by parsed version got it wrong twice - a 14-digit timestamp
     * version overflowed int and sorted first, and repeatables (no version) moved to the front.
     */
    @Test
    void map_keepsFlywaysOwnMigrationOrder() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(
                                        new FlywayResponse.Migration(
                                                null, "Second", null, null, null, null, null, "SUCCESS", null, "2.0"),
                                        new FlywayResponse.Migration(
                                                null, "Tenth", null, null, null, null, null, "SUCCESS", null, "10.0"),
                                        new FlywayResponse.Migration(
                                                null,
                                                "Timestamped",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "SUCCESS",
                                                null,
                                                "20240101120000"),
                                        new FlywayResponse.Migration(
                                                null,
                                                "Repeatable view",
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                "SUCCESS",
                                                null,
                                                null)))),
                        null)));

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations())
                .extracting(MigrationInfo::version)
                .containsExactly("2.0", "10.0", "20240101120000", null);
    }

    @Test
    void map_shouldHandleNullInput() {
        FlywayInfo result = mapper.map(null);
        assertThat(result.migrations()).isEmpty();
    }

    @Test
    void map_shouldHandleNullContexts() {
        FlywayResponse flywayData = new FlywayResponse(null);
        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations()).isEmpty();
    }

    @Test
    void map_shouldHandlePendingState() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(new FlywayResponse.Migration(
                                        null, null, null, null, null, null, null, "PENDING", null, "1")))),
                        null)));

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void map_shouldParseExecutionTime() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(new FlywayResponse.Migration(
                                        null, null, 250, null, null, null, null, "SUCCESS", null, "1")))),
                        null)));

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).executionTime()).isEqualTo(250);
    }

    @Test
    void map_shouldParseInstalledOnDate() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(new FlywayResponse.Migration(
                                        null,
                                        null,
                                        null,
                                        null,
                                        "2024-01-01T10:00:00Z",
                                        null,
                                        null,
                                        "SUCCESS",
                                        null,
                                        "1")))),
                        null)));

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations().get(0).installedOn()).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
    }

    @Test
    void map_shouldTolerateMalformedInstalledOnDate() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of(
                                "flyway",
                                new FlywayResponse.FlywayBean(List.of(new FlywayResponse.Migration(
                                        null, null, null, null, "not-a-date", null, null, "SUCCESS", null, "1")))),
                        null)));

        try (LogCapture capture = LogCapture.attach(FlywayMapper.class, Level.DEBUG)) {
            FlywayInfo result = mapper.map(flywayData);

            assertThat(result.migrations().get(0).installedOn()).isNull();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
                assertThat(event.getFormattedMessage()).isEqualTo("Failed to parse installedOn date: not-a-date");
            });
        }
    }
}
