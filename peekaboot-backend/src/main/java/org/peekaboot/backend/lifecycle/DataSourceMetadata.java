package org.peekaboot.backend.lifecycle;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What one {@code DataSource} bean connects to, read once at startup from a live
 * connection's {@link DatabaseMetaData} and the JDBC URL it reports.
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

    private static final Logger logger = LoggerFactory.getLogger(DataSourceMetadata.class);

    public DataSourceMetadata {
        hosts = hosts == null ? List.of() : hosts;
        connectionParams = connectionParams == null ? Map.of() : connectionParams;
    }

    public static Optional<DataSourceMetadata> fromDataSource(String dataSourceName, DataSource dataSource) {

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String username = metaData.getUserName();
            JdbcUrl jdbcUrl = JdbcUrlParser.parse(url);

            return Optional.of(new DataSourceMetadata(
                    dataSourceName,
                    username,
                    jdbcUrl.hosts(),
                    jdbcUrl.databaseName(),
                    jdbcUrl.databaseProduct(),
                    jdbcUrl.properties(),
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion(),
                    metaData.getDriverName()));
        } catch (Exception e) {
            logger.warn("Failed to extract metadata from DataSource '{}': {}", dataSourceName, e.getMessage());
        }

        return Optional.empty();
    }
}
