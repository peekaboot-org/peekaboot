package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.FlywayResponse;
import org.peekaboot.backend.domain.flyway.FlywayInfo;
import org.peekaboot.backend.domain.flyway.MigrationInfo;
import org.peekaboot.backend.domain.flyway.MigrationState;

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
                        "Initial schema",
                        100,
                        Instant.parse("2024-01-01T10:00:00Z"),
                        "V1__Initial_schema.sql",
                        "SUCCESS",
                        "SQL",
                        "1"),
                new FlywayResponse.Migration(
                        "Add users",
                        50,
                        Instant.parse("2024-01-02T10:00:00Z"),
                        "V2__Add_users.sql",
                        "SUCCESS",
                        "SQL",
                        "2"));

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations())
                .extracting(
                        MigrationInfo::version,
                        MigrationInfo::description,
                        MigrationInfo::state,
                        MigrationInfo::executionTime,
                        MigrationInfo::installedOn,
                        MigrationInfo::script,
                        MigrationInfo::type)
                .containsExactly(
                        tuple(
                                "1",
                                "Initial schema",
                                MigrationState.SUCCESS,
                                100,
                                Instant.parse("2024-01-01T10:00:00Z"),
                                "V1__Initial_schema.sql",
                                "SQL"),
                        tuple(
                                "2",
                                "Add users",
                                MigrationState.SUCCESS,
                                50,
                                Instant.parse("2024-01-02T10:00:00Z"),
                                "V2__Add_users.sql",
                                "SQL"));
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

    /** A context without beans and a bean without migrations bind as empty; the tab shows nothing rather than failing. */
    @Test
    void absentBeansAndMigrationsReadAsNoMigrations() {
        FlywayResponse flywayData = new FlywayResponse(Map.of(
                "empty", new FlywayResponse.FlywayContext(null, null),
                "application",
                        new FlywayResponse.FlywayContext(Map.of("flyway", new FlywayResponse.FlywayBean(null)), null)));

        assertThat(mapper.map(flywayData).migrations()).isEmpty();
    }
}
