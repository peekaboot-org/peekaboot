package org.peekaboot.backend.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.health.HealthConnectionSource;

/** Names each DataSource's target from its configured JDBC URL. Never asks a DataSource for a connection. */
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
                .map(JdbcUrlParser::tryParse)
                .ifPresent(jdbcUrl -> connections.put(beanName, describe(beanName, jdbcUrl))));
        return connections;
    }

    private static DataSourceConnection describe(String beanName, JdbcUrl jdbcUrl) {
        return new DataSourceConnection(
                beanName,
                jdbcUrl.databaseProduct().name(),
                jdbcUrl.hosts().stream()
                        .map(DataSourceConnections::withoutCredentials)
                        .filter(host ->
                                host.hostname() != null && !host.hostname().isBlank())
                        .toList(),
                jdbcUrl.databaseName(),
                instanceOf(jdbcUrl));
    }

    // the parser leaves "user:password@" in the hostname of a URL that carries credentials
    private static HealthConnection.Host withoutCredentials(Host host) {
        String hostname = host.hostname();
        return new HealthConnection.Host(
                hostname == null ? null : hostname.substring(hostname.lastIndexOf('@') + 1), host.port());
    }

    private static @Nullable String instanceOf(JdbcUrl jdbcUrl) {
        List<Host> hosts = jdbcUrl.hosts();
        if (!hosts.isEmpty() && hosts.getFirst().instanceName() != null) {
            return hosts.getFirst().instanceName();
        }
        return jdbcUrl.getPropertyValue("instanceName");
    }
}
