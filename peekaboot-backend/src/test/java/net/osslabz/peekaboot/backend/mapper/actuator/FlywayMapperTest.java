package net.osslabz.peekaboot.backend.mapper.actuator;

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
        Map<String, Object> flywayData = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "flywayBeans", Map.of(
                        "flyway", Map.of(
                            "migrations", List.of(
                                Map.of("version", "1", "description", "Initial schema", "type", "SQL", "state", "SUCCESS", "script", "V1__Initial_schema.sql"),
                                Map.of("version", "2", "description", "Add users", "type", "SQL", "state", "SUCCESS", "script", "V2__Add_users.sql")
                            )
                        )
                    )
                )
            )
        );

        FlywayInfo result = mapper.map(flywayData);

        assertThat(result.migrations()).hasSize(2);
        assertThat(result.migrations().get(0).version()).isEqualTo("1");
        assertThat(result.migrations().get(0).description()).isEqualTo("Initial schema");
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.SUCCESS);
    }

    @Test
    void map_shouldSortMigrationsByVersion() {
        Map<String, Object> flywayData = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "flywayBeans", Map.of(
                        "flyway", Map.of(
                            "migrations", List.of(
                                Map.of("version", "2.0", "description", "Second", "state", "SUCCESS"),
                                Map.of("version", "1.0", "description", "First", "state", "SUCCESS"),
                                Map.of("version", "10.0", "description", "Tenth", "state", "SUCCESS")
                            )
                        )
                    )
                )
            )
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
    void map_shouldHandleMissingContexts() {
        FlywayInfo result = mapper.map(Map.of());
        assertThat(result.migrations()).isEmpty();
    }

    @Test
    void map_shouldHandlePendingState() {
        Map<String, Object> flywayData = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "flywayBeans", Map.of(
                        "flyway", Map.of(
                            "migrations", List.of(
                                Map.of("version", "1", "state", "PENDING")
                            )
                        )
                    )
                )
            )
        );

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).state()).isEqualTo(MigrationState.PENDING);
    }

    @Test
    void map_shouldParseExecutionTime() {
        Map<String, Object> flywayData = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "flywayBeans", Map.of(
                        "flyway", Map.of(
                            "migrations", List.of(
                                Map.of("version", "1", "state", "SUCCESS", "executionTime", 250)
                            )
                        )
                    )
                )
            )
        );

        FlywayInfo result = mapper.map(flywayData);
        assertThat(result.migrations().get(0).executionTime()).isEqualTo(250);
    }
}
