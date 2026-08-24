package org.peekaboot.backend.mapper.actuator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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

        migrations.sort(Comparator.comparing(MigrationInfo::version, this::compareVersions));
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

    private int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) {
            return 0;
        }
        if (v1 == null) {
            return -1;
        }
        if (v2 == null) {
            return 1;
        }

        String[] parts1 = v1.split("\\.", -1);
        String[] parts2 = v2.split("\\.", -1);

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (p1 != p2) {
                return Integer.compare(p1, p2);
            }
        }
        return 0;
    }

    private int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
