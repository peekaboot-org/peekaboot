package org.peekaboot.backend.datasource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
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
 * <p>Only a URL {@link CredentialFreeJdbcUrls} accepts is parsed. A host that is no plain host name or IP
 * literal, or a database or instance that is no plain name or file path, still gives no connection.
 */
public final class DataSourceConnections implements HealthConnectionSource {

    private static final Pattern PLAIN_HOST = Pattern.compile("[A-Za-z0-9._-]+|\\[[0-9A-Fa-f:.]+]");
    // "/" for the file paths of H2, Derby, HSQLDB and SQLite, "~" for H2's home directory; empty where
    // the URL names no database
    private static final Pattern PLAIN_NAME = Pattern.compile("[A-Za-z0-9._$~/-]*");

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
        return CredentialFreeJdbcUrls.of(configuredUrl)
                .map(JdbcUrlParser::tryParse)
                .filter(jdbcUrl -> !isSqlServerWithAmpersand(jdbcUrl, configuredUrl))
                .filter(DataSourceConnections::namesPlainHostsOnly)
                .map(jdbcUrl -> describe(beanName, jdbcUrl))
                .filter(connection -> namesPlainDatabaseAndInstance(connection, configuredUrl));
    }

    // SQL Server splits properties on ";" only, so an "&" sits inside a value the parser splits apart
    private static boolean isSqlServerWithAmpersand(JdbcUrl jdbcUrl, String configuredUrl) {
        return jdbcUrl.databaseProduct() == DatabaseProduct.SQLSERVER && configuredUrl.indexOf('&') >= 0;
    }

    private static boolean namesPlainHostsOnly(JdbcUrl jdbcUrl) {
        return jdbcUrl.hosts().stream()
                .allMatch(host -> host.hostname() != null
                        && PLAIN_HOST.matcher(host.hostname()).matches());
    }

    private static boolean namesPlainDatabaseAndInstance(DataSourceConnection connection, String configuredUrl) {
        return isAbsentOrPlainName(connection.database(), configuredUrl)
                && isAbsentOrPlainName(connection.instance(), configuredUrl);
    }

    // the cut can take text out of a file path, and drivers rewrite names (whitespace, case, decoding)
    private static boolean isAbsentOrPlainName(@Nullable String name, String configuredUrl) {
        return name == null
                || (PLAIN_NAME.matcher(name).matches()
                        && configuredUrl.toLowerCase(Locale.ROOT).contains(name.toLowerCase(Locale.ROOT)));
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
        if (!hosts.isEmpty() && hosts.getFirst().instanceName() != null) {
            return hosts.getFirst().instanceName();
        }
        return jdbcUrl.getPropertyValue("instanceName");
    }
}
