package net.osslabz.peekaboot.backend.mapper.actuator;

import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.peekaboot.backend.domain.datasource.DataSourceInfo;
import net.osslabz.peekaboot.backend.domain.health.HealthStatus;
import net.osslabz.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Component
public class DataSourceMapper {

    private static final Pattern SENSITIVE_PATTERN = Pattern.compile(
        "password|secret|key|token|credential", Pattern.CASE_INSENSITIVE
    );

    public List<DataSourceInfo> map(List<DataSourceMetadata> metadataList, Map<String, Object> healthComponents) {
        if (metadataList == null || metadataList.isEmpty()) {
            return List.of();
        }

        HealthStatus dbHealth = extractDbHealth(healthComponents);

        return metadataList.stream()
            .filter(m -> m != null)
            .map(m -> mapSingle(m, dbHealth))
            .toList();
    }

    private DataSourceInfo mapSingle(DataSourceMetadata metadata, HealthStatus dbHealth) {
        List<net.osslabz.jdbc.Host> hosts = metadata.getHosts() != null ? metadata.getHosts() : List.of();
        Map<String, String> maskedProperties = maskSensitiveProperties(metadata.getConnectionParams());
        DatabaseProduct product = detectDatabaseProduct(metadata);

        return new DataSourceInfo(
            metadata.getDataSourceName(),
            product,
            metadata.getDriverName(),
            hosts,
            metadata.getDatabaseName(),
            null,
            metadata.getUsername(),
            null,
            dbHealth,
            maskedProperties
        );
    }

    private DatabaseProduct detectDatabaseProduct(DataSourceMetadata metadata) {
        String productName = metadata.getDatabaseProductName();
        if (productName == null) return DatabaseProduct.UNKNOWN;

        String lower = productName.toLowerCase();
        if (lower.contains("postgresql")) return DatabaseProduct.POSTGRESQL;
        if (lower.contains("mysql")) return DatabaseProduct.MYSQL;
        if (lower.contains("mariadb")) return DatabaseProduct.MARIADB;
        if (lower.contains("h2")) return DatabaseProduct.H2;
        if (lower.contains("oracle")) return DatabaseProduct.ORACLE;
        if (lower.contains("sql server")) return DatabaseProduct.SQLSERVER;
        if (lower.contains("sqlite")) return DatabaseProduct.SQLITE;
        if (lower.contains("derby")) return DatabaseProduct.DERBY;
        if (lower.contains("hsql")) return DatabaseProduct.HSQLDB;
        return DatabaseProduct.UNKNOWN;
    }

    private Map<String, String> maskSensitiveProperties(Map<String, JdbcProperty> properties) {
        if (properties == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JdbcProperty> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().value() : null;

            if (SENSITIVE_PATTERN.matcher(key).find()) {
                result.put(key, "********");
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private HealthStatus extractDbHealth(Map<String, Object> healthComponents) {
        if (healthComponents == null) return HealthStatus.UNKNOWN;

        Object dbObj = healthComponents.get("db");
        if (!(dbObj instanceof Map<?, ?> db)) return HealthStatus.UNKNOWN;

        Object statusObj = db.get("status");
        if (statusObj == null) return HealthStatus.UNKNOWN;

        String statusStr;
        if (statusObj instanceof Map<?, ?> m) {
            Object code = m.get("code");
            statusStr = code != null ? code.toString() : String.valueOf(m.get("name"));
        } else {
            statusStr = statusObj.toString();
        }

        return HealthStatus.fromString(statusStr);
    }
}
