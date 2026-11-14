package org.peekaboot.backend.datasource;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;

/**
 * What one {@code DataSource} bean connects to, read from a live connection's
 * {@link DatabaseMetaData} and the JDBC URL it reports.
 *
 * @param databaseProduct what the JDBC URL names, so a MariaDB reached through a
 *                        {@code jdbc:mysql:} URL reports MySQL
 */
public record DataSourceMetadata(
        String dataSourceName,
        String username,
        List<Host> hosts,
        String databaseName,
        DatabaseProduct databaseProduct,
        Map<String, JdbcProperty> connectionParams,
        String databaseProductName,
        String databaseProductVersion,
        String driverName) {

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

            return new DataSourceMetadata(
                    dataSourceName,
                    username,
                    jdbcUrl.hosts(),
                    jdbcUrl.databaseName(),
                    jdbcUrl.databaseProduct(),
                    jdbcUrl.properties(),
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion(),
                    metaData.getDriverName());
        }
    }
}
