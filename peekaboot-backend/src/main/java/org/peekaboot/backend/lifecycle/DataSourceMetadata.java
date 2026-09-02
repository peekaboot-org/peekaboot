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

public final class DataSourceMetadata {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceMetadata.class);

    private final String dataSourceName;

    private final String username;

    private final List<Host> hosts;

    private final String databaseName;

    private final DatabaseProduct databaseProduct;

    private final Map<String, JdbcProperty> connectionParams;

    private final String databaseProductName;

    private final String databaseProductVersion;

    private final String driverName;

    private DataSourceMetadata(
            String dataSourceName,
            String username,
            List<Host> hosts,
            String databaseName,
            DatabaseProduct databaseProduct,
            Map<String, JdbcProperty> connectionParams,
            String databaseProductName,
            String databaseProductVersion,
            String driverName) {

        this.dataSourceName = dataSourceName;
        this.username = username;
        this.hosts = hosts;
        this.databaseName = databaseName;
        this.databaseProduct = databaseProduct;
        this.connectionParams = connectionParams;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
        this.driverName = driverName;
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

    public String getDataSourceName() {

        return dataSourceName;
    }

    public String getUsername() {

        return username;
    }

    public List<Host> getHosts() {

        return hosts;
    }

    public String getDatabaseName() {

        return databaseName;
    }

    /** What the JDBC URL names, so a MariaDB reached through a {@code jdbc:mysql:} URL reports MySQL. */
    public DatabaseProduct getDatabaseProduct() {

        return databaseProduct;
    }

    public Map<String, JdbcProperty> getConnectionParams() {

        return connectionParams;
    }

    public String getDatabaseProductName() {

        return databaseProductName;
    }

    public String getDatabaseProductVersion() {

        return databaseProductVersion;
    }

    public String getDriverName() {

        return driverName;
    }
}
