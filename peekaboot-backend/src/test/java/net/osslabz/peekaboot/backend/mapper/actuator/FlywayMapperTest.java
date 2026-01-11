package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.actuator.raw.FlywayResponse;
import net.osslabz.peekaboot.backend.domain.flyway.FlywayInfo;
import net.osslabz.peekaboot.backend.domain.flyway.MigrationState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FlywayMapperTest {

    private final FlywayMapper mapper = new FlywayMapper();

    @Test
    void map_shouldExtractMigrations() {
        FlywayResponse flywayData = new FlywayResponse(
            Map.of("application", new FlywayResponse.FlywayContext(
                Map.of("flyway", new FlywayResponse.FlywayBean(List.of(
                    new FlywayResponse.Migration(12345L, "Initial schema", 100, "admin", "2024-01-01T10:00:00Z", 1, "V1__Initial_schema.sql", "SUCCESS", "SQL", "1"),
                    new FlywayResponse.Migration(12346L, "Add users", 50, "admin", "2024-01-02T10:00:00Z", 2, "V2__Add_users.sql", "SUCCESS", "SQL", "2")
                ))),
                null
            ))
        );

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations()).hasSize(2);
        assertThat(result.migrations().get(0).version()).isEqualTo("1");
        assertThat(result.migrations().get(0).description()).isEqualTo("Initial schema");
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void map_shouldSortMigrationsByVersion() {
        FlywayResponse flywayData = new FlywayResponse(
            Map.of("application", new FlywayResponse.FlywayContext(
                Map.of("flyway", new FlywayResponse.FlywayBean(List.of(
                    new FlywayResponse.Migration(null, "Second", null, null, null, null, null, "SUCCESS", null, "2.0"),
                    new FlywayResponse.Migration(null, "First", null, null, null, null, null, "SUCCESS", null, "1.0"),
                    new FlywayResponse.Migration(null, "Tenth", null, null, null, null, null, "SUCCESS", null, "10.0")
                ))),
                null
            ))
        );

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations().get(0).version()).isEqualTo("1.0");
        assertThat(result.migrations().get(1).version()).isEqualTo("2.0");
        assertThat(result.migrations().get(2).version()).isEqualTo("10.0");
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
        FlywayResponse flywayData = new FlywayResponse(
            Map.of("application", new FlywayResponse.FlywayContext(
                Map.of("flyway", new FlywayResponse.FlywayBean(List.of(
                    new FlywayResponse.Migration(null, null, null, null, null, null, null, "PENDING", null, "1")
                ))),
                null
            ))
        );

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void map_shouldParseExecutionTime() {
        FlywayResponse flywayData = new FlywayResponse(
            Map.of("application", new FlywayResponse.FlywayContext(
                Map.of("flyway", new FlywayResponse.FlywayBean(List.of(
                    new FlywayResponse.Migration(null, null, 250, null, null, null, null, "SUCCESS", null, "1")
                ))),
                null
            ))
        );

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).executionTime()).isEqualTo(250);
    }
}
