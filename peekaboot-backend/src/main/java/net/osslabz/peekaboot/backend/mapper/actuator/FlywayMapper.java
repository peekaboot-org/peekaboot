package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.peekaboot.backend.domain.flyway.FlywayInfo;
import net.osslabz.peekaboot.backend.domain.flyway.MigrationInfo;
import net.osslabz.peekaboot.backend.domain.flyway.MigrationState;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Component
public class FlywayMapper {

    @SuppressWarnings("unchecked")
    public FlywayInfo map(Map<String, Object> flywayData) {
        if (flywayData == null) {
            return new FlywayInfo(List.of());
        }

        List<MigrationInfo> migrations = new ArrayList<>();

        Object contextsObj = flywayData.get("contexts");
        if (!(contextsObj instanceof Map<?, ?> contexts)) {
            return new FlywayInfo(List.of());
        }

        for (Object contextValue : contexts.values()) {
            if (!(contextValue instanceof Map<?, ?> context)) continue;

            Object beansObj = context.get("flywayBeans");
            if (!(beansObj instanceof Map<?, ?> beans)) continue;

            for (Object beanValue : beans.values()) {
                if (!(beanValue instanceof Map<?, ?> bean)) continue;

                Object migrationsObj = bean.get("migrations");
                if (!(migrationsObj instanceof List<?> migrationsList)) continue;

                for (Object migrationObj : migrationsList) {
                    if (migrationObj instanceof Map<?, ?> migration) {
                        migrations.add(mapMigration((Map<String, Object>) migration));
                    }
                }
            }
        }

        migrations.sort(Comparator.comparing(MigrationInfo::version, this::compareVersions));
        return new FlywayInfo(migrations);
    }

    private MigrationInfo mapMigration(Map<String, Object> migration) {
        String version = getStringValue(migration, "version");
        String description = getStringValue(migration, "description");
        String type = getStringValue(migration, "type");
        MigrationState state = MigrationState.fromString(getStringValue(migration, "state"));
        String script = getStringValue(migration, "script");

        Instant installedOn = null;
        Object installedOnObj = migration.get("installedOn");
        if (installedOnObj != null) {
            try {
                installedOn = Instant.parse(installedOnObj.toString());
            } catch (Exception ignored) {
            }
        }

        Integer executionTime = null;
        Object execTimeObj = migration.get("executionTime");
        if (execTimeObj instanceof Number n) {
            executionTime = n.intValue();
        }

        return new MigrationInfo(version, description, type, state, installedOn, executionTime, script);
    }

    private int compareVersions(String v1, String v2) {
        if (v1 == null && v2 == null) return 0;
        if (v1 == null) return -1;
        if (v2 == null) return 1;

        String[] parts1 = v1.split("\\.");
        String[] parts2 = v2.split("\\.");

        int maxLen = Math.max(parts1.length, parts2.length);
        for (int i = 0; i < maxLen; i++) {
            int p1 = i < parts1.length ? parseIntSafe(parts1[i]) : 0;
            int p2 = i < parts2.length ? parseIntSafe(parts2[i]) : 0;
            if (p1 != p2) return Integer.compare(p1, p2);
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

    private String getStringValue(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }
}