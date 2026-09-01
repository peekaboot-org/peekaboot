package org.peekaboot.backend.domain.datasource;

import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import org.peekaboot.backend.domain.health.HealthStatus;

/** One datasource, its health taken from the health actuator and its connection properties masked. */
public record DataSourceInfo(
        String name,
        DatabaseProduct databaseProduct,
        String driver,
        List<Host> hosts,
        String databaseName,
        String username,
        HealthStatus health,
        Map<String, String> properties) {}
