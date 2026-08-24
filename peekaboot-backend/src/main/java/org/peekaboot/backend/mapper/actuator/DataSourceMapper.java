package org.peekaboot.backend.mapper.actuator;

import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import org.peekaboot.backend.actuator.raw.HealthResponse;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.masking.MaskingEngine;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;

@Component
public class DataSourceMapper {

    private final MaskingEngine maskingEngine = new MaskingEngine();

    public List<DataSourceInfo> map(List<DataSourceMetadata> metadataList, HealthResponse health) {
        return map(metadataList, health, false);
    }

    /**
     * Same as {@link #map(List, HealthResponse)}, except when {@code unmask} is true, in
     * which case every connection property value is returned verbatim. See
     * {@link MaskingEngine#mask(String, String, boolean)} for why this shape.
     */
    public List<DataSourceInfo> map(List<DataSourceMetadata> metadataList, HealthResponse health, boolean unmask) {
        if (metadataList == null || metadataList.isEmpty()) {
            return List.of();
        }

        HealthStatus dbHealth = extractDbHealth(health);

        return metadataList.stream()
            .filter(m -> m != null)
            .map(m -> mapSingle(m, dbHealth, unmask))
            .toList();
    }

    private DataSourceInfo mapSingle(DataSourceMetadata metadata, HealthStatus dbHealth, boolean unmask) {
        List<net.osslabz.jdbc.Host> hosts = metadata.getHosts() != null ? metadata.getHosts() : List.of();
        Map<String, String> maskedProperties = maskSensitiveProperties(metadata.getConnectionParams(), unmask);
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

    private static final List<Map.Entry<String, DatabaseProduct>> PRODUCT_KEYWORDS = List.of(
        Map.entry("postgresql", DatabaseProduct.POSTGRESQL),
        Map.entry("mysql", DatabaseProduct.MYSQL),
        Map.entry("mariadb", DatabaseProduct.MARIADB),
        Map.entry("h2", DatabaseProduct.H2),
        Map.entry("oracle", DatabaseProduct.ORACLE),
        Map.entry("sql server", DatabaseProduct.SQLSERVER),
        Map.entry("sqlite", DatabaseProduct.SQLITE),
        Map.entry("derby", DatabaseProduct.DERBY),
        Map.entry("hsql", DatabaseProduct.HSQLDB)
    );

    private DatabaseProduct detectDatabaseProduct(DataSourceMetadata metadata) {
        String productName = metadata.getDatabaseProductName();
        if (productName == null) return DatabaseProduct.UNKNOWN;

        String lower = productName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, DatabaseProduct> keyword : PRODUCT_KEYWORDS) {
            if (lower.contains(keyword.getKey())) {
                return keyword.getValue();
            }
        }
        return DatabaseProduct.UNKNOWN;
    }

    private Map<String, String> maskSensitiveProperties(Map<String, JdbcProperty> properties, boolean unmask) {
        if (properties == null) return Map.of();

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JdbcProperty> entry : properties.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue() != null ? entry.getValue().value() : null;

            result.put(key, maskingEngine.mask(key, value, unmask));
        }
        return result;
    }

    private HealthStatus extractDbHealth(HealthResponse health) {
        if (health == null || health.body() == null || health.body().components() == null) {
            return HealthStatus.UNKNOWN;
        }

        HealthResponse.HealthComponent db = health.body().components().get("db");
        if (db == null) return HealthStatus.UNKNOWN;

        return HealthStatus.fromString(db.status());
    }
}
