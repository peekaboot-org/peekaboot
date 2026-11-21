package org.peekaboot.backend.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.health.HealthConnectionSource;

/**
 * Names each DataSource's target from its configured JDBC URL. Never asks a DataSource for a connection.
 *
 * <p>A URL with an {@code @} after its {@code //}, or with any host that is no plain host name or IP
 * literal, gets no connection: there the parser can cut a user or password into the hostname, port or
 * database.
 */
public final class DataSourceConnections implements HealthConnectionSource {

    private static final Pattern PLAIN_HOST = Pattern.compile("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+]");

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
                .filter(DataSourceConnections::carriesNoUserInfo)
                .map(JdbcUrlParser::tryParse)
                .filter(DataSourceConnections::namesPlainHostsOnly)
                .ifPresent(jdbcUrl -> connections.put(beanName, describe(beanName, jdbcUrl))));
        return connections;
    }

    private static boolean carriesNoUserInfo(String url) {
        int authority = url.indexOf("//");
        return authority < 0 || url.indexOf('@', authority) < 0;
    }

    private static boolean namesPlainHostsOnly(JdbcUrl jdbcUrl) {
        return jdbcUrl.hosts().stream()
                .allMatch(host -> host.hostname() != null
                        && PLAIN_HOST.matcher(host.hostname()).matches());
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
        List<Host> hosts = jdbcUrl.hosts();
        if (!hosts.isEmpty() && hosts.getFirst().instanceName() != null) {
            return hosts.getFirst().instanceName();
        }
        return jdbcUrl.getPropertyValue("instanceName");
    }
}
