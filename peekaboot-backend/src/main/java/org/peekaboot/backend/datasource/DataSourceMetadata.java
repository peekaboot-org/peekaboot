package org.peekaboot.backend.datasource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import net.osslabz.jdbc.PropertySource;
import org.jspecify.annotations.Nullable;

/**
 * What one {@code DataSource} bean connects to, read from a live connection's
 * {@link DatabaseMetaData} and the JDBC URL it reports.
 *
 * @param hosts            empty where the JDBC URL could hide a user or password in its target
 * @param databaseName     null where the JDBC URL could hide a user or password in its target
 * @param databaseProduct  what the JDBC URL names, so a MariaDB reached through a
 *                         {@code jdbc:mysql:} URL reports MySQL
 * @param connectionParams the JDBC URL's properties, without the user, password and host attributes of
 *                         its authority and without an Oracle descriptor's; empty where the JDBC URL could
 *                         hide a user or password in its target
 */
public record DataSourceMetadata(
        String dataSourceName,
        String username,
        List<Host> hosts,
        @Nullable String databaseName,
        DatabaseProduct databaseProduct,
        Map<String, JdbcProperty> connectionParams,
        String databaseProductName,
        String databaseProductVersion,
        String driverName) {

    // the authority holds the user and password; masking sees a descriptor's key, never the keys inside it
    private static final Set<PropertySource> UNSHOWN_SOURCES =
            EnumSet.of(PropertySource.AUTHORITY, PropertySource.DESCRIPTOR);

    public DataSourceMetadata {
        hosts = hosts == null ? List.of() : hosts;
        connectionParams = connectionParams == null ? Map.of() : connectionParams;
    }

    /** Leaves out connectionParams: a JDBC URL can carry the password, and a record's toString reaches logs. */
    @Override
    public String toString() {
        return "DataSourceMetadata[dataSourceName=" + dataSourceName + ", username=" + username + ", hosts=" + hosts
                + ", databaseName=" + databaseName + ", databaseProduct=" + databaseProduct
                + ", databaseProductName=" + databaseProductName + ", databaseProductVersion="
                + databaseProductVersion + ", driverName=" + driverName + "]";
    }

    /** Opens a connection. Throws what the pool, the driver or the URL parser throws. */
    public static DataSourceMetadata read(String dataSourceName, DataSource dataSource) throws SQLException {

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String username = metaData.getUserName();
            JdbcUrl jdbcUrl = JdbcUrlParser.parse(url);
            Optional<JdbcUrl> target = CredentialFreeJdbcUrls.parse(url);

            return new DataSourceMetadata(
                    dataSourceName,
                    username,
                    target.map(JdbcUrl::hosts).orElse(List.of()),
                    target.map(JdbcUrl::databaseName).orElse(null),
                    jdbcUrl.databaseProduct(),
                    target.isPresent() ? shownParams(jdbcUrl.properties()) : Map.of(),
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion(),
                    metaData.getDriverName());
        }
    }

    private static Map<String, JdbcProperty> shownParams(Map<String, JdbcProperty> properties) {
        return properties.entrySet().stream()
                .filter(property ->
                        !UNSHOWN_SOURCES.contains(property.getValue().source()))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
