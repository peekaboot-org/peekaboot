package org.peekaboot.backend.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcUrl;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.health.HealthConnectionSource;

/**
 * Names each DataSource's target from its configured JDBC URL. Never asks a DataSource for a connection.
 *
 * <p>A URL {@link CredentialFreeJdbcUrls} refuses gives no connection. Its rules include the check that the URL
 * spells out every host, database and instance the parser names.
 */
public final class DataSourceConnections implements HealthConnectionSource {

    private final Map<String, DataSource> dataSources;
    private final Function<DataSource, Optional<String>> configuredUrl;

    public DataSourceConnections(
            Map<String, DataSource> dataSources, Function<DataSource, Optional<String>> configuredUrl) {
        this.dataSources = dataSources;
        this.configuredUrl = configuredUrl;
    }

    @Override
    public String component() {
        return "db";
    }

    @Override
    public Map<String, HealthConnection> connections() {
        Map<String, HealthConnection> connections = new LinkedHashMap<>();
        dataSources.forEach((beanName, dataSource) -> configuredUrl
                .apply(dataSource)
                .flatMap(url -> describe(beanName, url))
                .ifPresent(connection -> connections.put(beanName, connection)));
        return connections;
    }

    private static Optional<DataSourceConnection> describe(String beanName, String configuredUrl) {
        return CredentialFreeJdbcUrls.parse(configuredUrl).map(jdbcUrl -> describe(beanName, jdbcUrl));
    }

    private static DataSourceConnection describe(String beanName, JdbcUrl jdbcUrl) {
        return new DataSourceConnection(
                beanName,
                jdbcUrl.databaseProduct().name(),
                jdbcUrl.hosts().stream()
                        .map(host -> new HealthConnection.Host(host.hostname(), host.port()))
                        .toList(),
                jdbcUrl.databaseName(),
                instanceOf(jdbcUrl));
    }

    private static @Nullable String instanceOf(JdbcUrl jdbcUrl) {
        if (jdbcUrl.databaseProduct() != DatabaseProduct.SQLSERVER) {
            return null;
        }
        List<Host> hosts = jdbcUrl.hosts();
        return hosts.isEmpty() ? null : hosts.getFirst().instanceName();
    }
}
