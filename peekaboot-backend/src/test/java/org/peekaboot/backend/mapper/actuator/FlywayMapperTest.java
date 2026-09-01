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

    /** The endpoint's shape around one Flyway bean's migrations: one context, one bean. */
    private static FlywayResponse flyway(FlywayResponse.Migration... migrations) {
        return new FlywayResponse(Map.of(
                "application",
                new FlywayResponse.FlywayContext(
                        Map.of("flyway", new FlywayResponse.FlywayBean(List.of(migrations))), null)));
    }

    private static FlywayResponse.Migration migration(String description, String state, String version) {
        return new FlywayResponse.Migration(description, null, null, null, state, null, version);
    }

    @Test
    void map_shouldExtractMigrations() {
        FlywayResponse flywayData = flyway(
                new FlywayResponse.Migration(
                        "Initial schema", 100, "2024-01-01T10:00:00Z", "V1__Initial_schema.sql", "SUCCESS", "SQL", "1"),
                new FlywayResponse.Migration(
                        "Add users", 50, "2024-01-02T10:00:00Z", "V2__Add_users.sql", "SUCCESS", "SQL", "2"));

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
        FlywayResponse flywayData = flyway(
                migration("Second", "SUCCESS", "2.0"),
                migration("Tenth", "SUCCESS", "10.0"),
                migration("Timestamped", "SUCCESS", "20240101120000"),
                migration("Repeatable view", "SUCCESS", null));

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
        FlywayInfo result = mapper.map(flyway(migration(null, "PENDING", "1")));
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void map_shouldParseExecutionTime() {
        FlywayResponse flywayData = flyway(new FlywayResponse.Migration(null, 250, null, null, "SUCCESS", null, "1"));

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).executionTime()).isEqualTo(250);
    }

    @Test
    void map_shouldParseInstalledOnDate() {
        FlywayResponse flywayData =
                flyway(new FlywayResponse.Migration(null, null, "2024-01-01T10:00:00Z", null, "SUCCESS", null, "1"));

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations().get(0).installedOn()).isEqualTo(Instant.parse("2024-01-01T10:00:00Z"));
    }

    @Test
    void map_shouldTolerateMalformedInstalledOnDate() {
        FlywayResponse flywayData =
                flyway(new FlywayResponse.Migration(null, null, "not-a-date", null, "SUCCESS", null, "1"));

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
