package org.peekaboot.backend.mapper.actuator;

import java.util.ArrayList;
import java.util.List;
import org.peekaboot.backend.actuator.parsed.FlywayResponse;
import org.peekaboot.backend.domain.flyway.FlywayInfo;
import org.peekaboot.backend.domain.flyway.MigrationInfo;
import org.peekaboot.backend.domain.flyway.MigrationState;

public class FlywayMapper {

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
        // parsed version would put 14-digit timestamp versions first and repeatables ahead of all.
        return new FlywayInfo(migrations);
    }

    private MigrationInfo mapMigration(FlywayResponse.Migration migration) {
        return new MigrationInfo(
                migration.version(),
                migration.description(),
                migration.type(),
                MigrationState.fromString(migration.state()),
                migration.installedOn(),
                migration.executionTime(),
                migration.script());
    }
}
