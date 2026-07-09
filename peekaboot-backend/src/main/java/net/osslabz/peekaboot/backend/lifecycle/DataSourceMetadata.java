package net.osslabz.peekaboot.backend.lifecycle;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.JdbcUrl;
import net.osslabz.jdbc.JdbcUrlParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class DataSourceMetadata {

    private static final Logger logger = LoggerFactory.getLogger(DataSourceMetadata.class);

    private final String dataSourceName;

    private final String url;

    private final String username;


    public List<Host> getHosts() {

        return hosts;
    }


    private final List<Host> hosts;

    private final String databaseName;

    private final Map<String, JdbcProperty> connectionParams;

    private final String databaseProductName;

    private final String databaseProductVersion;

    private final String driverName;

    private final String driverVersion;


    private DataSourceMetadata(String dataSourceName, String url, String username, List<Host> hosts,
        String databaseName, Map<String, JdbcProperty> connectionParams,
        String databaseProductName, String databaseProductVersion,
        String driverName, String driverVersion) {

        this.dataSourceName = dataSourceName;
        this.url = url;
        this.username = username;
        this.hosts = hosts;
        this.databaseName = databaseName;
        this.connectionParams = connectionParams;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
        this.driverName = driverName;
        this.driverVersion = driverVersion;
    }


    public static Optional<DataSourceMetadata> fromDataSource(String dataSourceName, DataSource dataSource) {

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String username = metaData.getUserName();
            JdbcUrl jdbcUrl = JdbcUrlParser.parse(url);

            return Optional.of(new DataSourceMetadata(
                dataSourceName,
                url,
                username,
                jdbcUrl.hosts(),
                jdbcUrl.databaseName(),
                jdbcUrl.properties(),
                metaData.getDatabaseProductName(),
                metaData.getDatabaseProductVersion(),
                metaData.getDriverName(),
                metaData.getDriverVersion()
            ));
        } catch (Exception e) {
            logger.warn("Failed to extract metadata from DataSource '{}': {}", dataSourceName, e.getMessage());
        }

        return Optional.empty();
    }


    public String getDataSourceName() {

        return dataSourceName;
    }


    public String getUrl() {

        return url;
    }


    public String getUsername() {

        return username;
    }


    public String getDatabaseName() {

        return databaseName;
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


    public String getDriverVersion() {

        return driverVersion;
    }
}