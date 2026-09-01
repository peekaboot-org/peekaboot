package org.peekaboot.backend.mapper.actuator;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.masking.ConnectionParamsMasker;
import org.springframework.stereotype.Component;

@Component
public class DataSourceMapper {

    private final ConnectionParamsMasker connectionParamsMasker = new ConnectionParamsMasker();

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

        return new DataSourceInfo(
                metadata.getDataSourceName(),
                metadata.getDatabaseProduct(),
                metadata.getDriverName(),
                hosts,
                metadata.getDatabaseName(),
                metadata.getUsername(),
                dbHealth,
                maskedProperties);
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
