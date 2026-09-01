package org.peekaboot.backend.mapper.actuator;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.masking.ConnectionParamsMasker;
import org.springframework.stereotype.Component;

@Component
public class DataSourceMapper {

    private final ConnectionParamsMasker connectionParamsMasker = new ConnectionParamsMasker();

    public List<DataSourceInfo> map(List<DataSourceMetadata> metadataList, HealthResponse health) {
        return map(metadataList, health, false);
    }

    /**
     * Same as {@link #map(List, HealthResponse)}, except when {@code unmask} is true, in
     * which case every connection property value is returned verbatim. See
     * {@link ConnectionParamsMasker#mask(Map, boolean)} for why this shape.
     */
    public List<DataSourceInfo> map(List<DataSourceMetadata> metadataList, HealthResponse health, boolean unmask) {
        if (metadataList == null || metadataList.isEmpty()) {
            return List.of();
        }

        return metadataList.stream()
                .filter(m -> m != null)
                .map(m -> mapSingle(m, health, unmask))
                .toList();
    }

    private DataSourceInfo mapSingle(DataSourceMetadata metadata, HealthResponse health, boolean unmask) {
        HealthStatus dbHealth = extractDbHealth(health, metadata.getDataSourceName());
        List<net.osslabz.jdbc.Host> hosts = metadata.getHosts() != null ? metadata.getHosts() : List.of();
        Map<String, String> maskedProperties = connectionParamsMasker.mask(metadata.getConnectionParams(), unmask);
        DatabaseProduct product = detectDatabaseProduct(metadata);

        return new DataSourceInfo(
                metadata.getDataSourceName(),
                product,
                metadata.getDriverName(),
                hosts,
                metadata.getDatabaseName(),
                metadata.getUsername(),
                dbHealth,
                maskedProperties);
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
            Map.entry("hsql", DatabaseProduct.HSQLDB));

    private DatabaseProduct detectDatabaseProduct(DataSourceMetadata metadata) {
        String productName = metadata.getDatabaseProductName();
        if (productName == null) {
            return DatabaseProduct.UNKNOWN;
        }

        String lower = productName.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, DatabaseProduct> keyword : PRODUCT_KEYWORDS) {
            if (lower.contains(keyword.getKey())) {
                return keyword.getValue();
            }
        }
        return DatabaseProduct.UNKNOWN;
    }

    /**
     * With several DataSources Spring's {@code db} contributor is a composite with one
     * child per DataSource bean, so each row reads its own child's status; a DataSource
     * without a child of its own, and the single-DataSource case, get {@code db}'s status.
     */
    private HealthStatus extractDbHealth(HealthResponse health, String dataSourceName) {
        if (health == null || health.components() == null) {
            return HealthStatus.UNKNOWN;
        }

        HealthResponse.HealthComponent db = health.components().get("db");
        if (db == null) {
            return HealthStatus.UNKNOWN;
        }

        HealthResponse.HealthComponent own =
                db.components() != null ? db.components().get(dataSourceName) : null;
        return HealthStatus.fromString(own != null ? own.status() : db.status());
    }
}
