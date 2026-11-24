package org.peekaboot.backend.datasource;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import javax.sql.DataSource;

/** DataSources whose live connection reports a given JDBC URL, without a database behind it. */
public final class ReportingDataSources {

    private ReportingDataSources() {}

    /** Reports {@code url} as the connection's URL, user {@code app}, product MySQL 9 and driver {@code driver}. */
    public static DataSource reporting(String url) throws SQLException {
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(metaData.getURL()).thenReturn(url);
        when(metaData.getUserName()).thenReturn("app");
        when(metaData.getDatabaseProductName()).thenReturn("MySQL");
        when(metaData.getDatabaseProductVersion()).thenReturn("9");
        when(metaData.getDriverName()).thenReturn("driver");
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenReturn(metaData);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        return dataSource;
    }
}
