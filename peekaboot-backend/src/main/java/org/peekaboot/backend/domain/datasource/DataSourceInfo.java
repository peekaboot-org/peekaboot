package org.peekaboot.backend.domain.datasource;

import java.util.List;
import java.util.Map;
import org.peekaboot.backend.domain.health.HealthStatus;

/**
 * One datasource, its health taken from the health actuator and its connection properties masked.
 * The product and hosts are Peekaboot's own values, so a JDBC URL parser upgrade cannot change the JSON.
 *
 * @param databaseProduct the product's constant name, such as {@code POSTGRESQL}
 */
public record DataSourceInfo(
        String name,
        String databaseProduct,
        String driver,
        List<Host> hosts,
        String databaseName,
        String username,
        HealthStatus health,
        Map<String, String> properties) {

    public record Host(String hostname, Integer port, String instanceName) {}
}
