package net.osslabz.peekaboot.backend.lifecycle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.util.UriComponentsBuilder;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

public class DatabaseMetadata {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseMetadata.class);

    private final String dataSourceName;
    private final String url;
    private final String username;
    private final String host;
    private final int port;
    private final String databaseName;
    private final Map<String, String> connectionParams;
    private final String databaseProductName;
    private final String databaseProductVersion;
    private final String driverName;
    private final String driverVersion;

    private DatabaseMetadata(String dataSourceName, String url, String username, String host, int port,
                             String databaseName, Map<String, String> connectionParams,
                             String databaseProductName, String databaseProductVersion,
                             String driverName, String driverVersion) {
        this.dataSourceName = dataSourceName;
        this.url = url;
        this.username = username;
        this.host = host;
        this.port = port;
        this.databaseName = databaseName;
        this.connectionParams = connectionParams;
        this.databaseProductName = databaseProductName;
        this.databaseProductVersion = databaseProductVersion;
        this.driverName = driverName;
        this.driverVersion = driverVersion;
    }

    public static DatabaseMetadata fromDataSource(String name, DataSource dataSource) {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metaData = connection.getMetaData();
            String url = metaData.getURL();
            String username = metaData.getUserName();

            ConnectionInfo connInfo = parseJdbcUrl(url);

            return new DatabaseMetadata(
                    name,
                    url,
                    username,
                    connInfo.host,
                    connInfo.port,
                    connInfo.databaseName,
                    connInfo.params,
                    metaData.getDatabaseProductName(),
                    metaData.getDatabaseProductVersion(),
                    metaData.getDriverName(),
                    metaData.getDriverVersion()
            );
        } catch (SQLException e) {
            logger.warn("Failed to extract metadata from DataSource '{}': {}", name, e.getMessage());
            return createFallback(name, e.getMessage());
        }
    }

    private static ConnectionInfo parseJdbcUrl(String jdbcUrl) {
        try {
            if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:")) {
                return new ConnectionInfo("unknown", -1, "unknown", Map.of());
            }

            String cleanUrl = jdbcUrl.substring(5);

            int schemeEnd = cleanUrl.indexOf(':');
            if (schemeEnd == -1) {
                return new ConnectionInfo("unknown", -1, "unknown", Map.of());
            }

            String actualUrl = cleanUrl.substring(schemeEnd + 1);

            if (actualUrl.startsWith("//")) {
                URI uri = UriComponentsBuilder.fromUriString(actualUrl).build().toUri();
                String host = uri.getHost() != null ? uri.getHost() : "unknown";
                int port = uri.getPort();
                String path = uri.getPath();
                String databaseName = (path != null && path.length() > 1) ? path.substring(1) : "unknown";

                Map<String, String> params = new HashMap<>();
                String query = uri.getQuery();
                if (query != null) {
                    for (String param : query.split("&")) {
                        String[] parts = param.split("=", 2);
                        if (parts.length == 2) {
                            params.put(parts[0], parts[1]);
                        }
                    }
                }

                return new ConnectionInfo(host, port, databaseName, params);
            }

            return new ConnectionInfo("unknown", -1, "unknown", Map.of());

        } catch (Exception e) {
            logger.debug("Failed to parse JDBC URL: {}", jdbcUrl, e);
            return new ConnectionInfo("unknown", -1, "unknown", Map.of());
        }
    }

    private static DatabaseMetadata createFallback(String name, String errorMessage) {
        return new DatabaseMetadata(
                name, errorMessage, "unknown", "unknown", -1, "unknown",
                Map.of(), "unknown", "unknown", "unknown", "unknown"
        );
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

    public String getHost() {
        return host;
    }

    public int getPort() {
        return port;
    }

    public String getDatabaseName() {
        return databaseName;
    }

    public Map<String, String> getConnectionParams() {
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

    private static class ConnectionInfo {
        final String host;
        final int port;
        final String databaseName;
        final Map<String, String> params;

        ConnectionInfo(String host, int port, String databaseName, Map<String, String> params) {
            this.host = host;
            this.port = port;
            this.databaseName = databaseName;
            this.params = params;
        }
    }
}
