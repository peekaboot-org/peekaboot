package org.peekaboot.backend.domain.datasource;

import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import org.peekaboot.backend.domain.health.HealthStatus;

/**
 * Rich domain record for datasource information.
 *
 * Uses explicit typing because datasources:
 * - Cross-reference health status from health actuator
 * - Mask sensitive connection properties
 * - Parse JDBC URLs via net.osslabz.jdbc library
 */
public record DataSourceInfo(
        String name,
        DatabaseProduct databaseProduct,
        String driver,
        List<Host> hosts,
        String databaseName,
        String schema,
        String username,
        PoolMetrics pool,
        HealthStatus health,
        Map<String, String> properties) {}
