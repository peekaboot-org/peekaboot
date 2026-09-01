package org.peekaboot.backend.mapper.actuator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.actuator.parsed.FlywayResponse;
import org.peekaboot.backend.domain.flyway.FlywayInfo;
import org.peekaboot.backend.domain.flyway.MigrationInfo;
import org.peekaboot.backend.domain.flyway.MigrationState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FlywayMapper {

    private static final Logger log = LoggerFactory.getLogger(FlywayMapper.class);

    public FlywayInfo map(FlywayResponse flywayData) {
        if (flywayData == null || flywayData.contexts() == null) {
            return new FlywayInfo(List.of());
        }

        List<MigrationInfo> migrations = new ArrayList<>();

        for (FlywayResponse.FlywayContext context : flywayData.contexts().values()) {
            if (context.flywayBeans() == null) {
                continue;
            }

            for (FlywayResponse.FlywayBean bean : context.flywayBeans().values()) {
                if (bean.migrations() == null) {
                    continue;
                }

                for (FlywayResponse.Migration migration : bean.migrations()) {
                    migrations.add(mapMigration(migration));
                }
            }
        }

        // Flyway's own order is kept: versioned ascending, then repeatables. Re-sorting by a
        // parsed version put 14-digit timestamp versions first and repeatables ahead of all.
        return new FlywayInfo(migrations);
    }

    private MigrationInfo mapMigration(FlywayResponse.Migration migration) {
        MigrationState state = MigrationState.fromString(migration.state());

        Instant installedOn = null;
        if (migration.installedOn() != null) {
            try {
                installedOn = Instant.parse(migration.installedOn());
            } catch (Exception e) {
                log.debug("Failed to parse installedOn date: {}", migration.installedOn(), e);
            }
        }

        return new MigrationInfo(
                migration.version(),
                migration.description(),
                migration.type(),
                state,
                installedOn,
                migration.executionTime(),
                migration.script());
    }
}
