package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.testsupport.LogCapture;

class DataSourceMetadataTest {

    @Test
    void extractsMetadataFromWorkingDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test;DB_CLOSE_DELAY=-1");

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("primary", dataSource);

        assertThat(metadata).isPresent();
        assertThat(metadata.get().dataSourceName()).isEqualTo("primary");
        assertThat(metadata.get().databaseProductName()).isEqualTo("H2");
        assertThat(metadata.get().databaseProduct()).isEqualTo(DatabaseProduct.H2);
    }

    @Test
    void extractsConnectionDetailsAndDriverFromWorkingDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test-2;DB_CLOSE_DELAY=-1");

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("primary", dataSource);

        assertThat(metadata).isPresent();
        DataSourceMetadata m = metadata.get();
        assertThat(m.username()).isEqualTo("");
        assertThat(m.hosts()).isEmpty();
        assertThat(m.databaseName()).isEqualTo("metadata-test-2");
        // DB_CLOSE_DELAY is not asserted even though the configured URL sets it:
        // connectionParams derive from DatabaseMetaData.getURL(), the driver-reported URL,
        // and H2 strips session-only params like DB_CLOSE_DELAY from it. MODE is the only
        // connection param this path can observe for an H2 in-memory URL.
        assertThat(m.connectionParams()).containsKey("MODE");
        assertThat(m.connectionParams().get("MODE").value()).isEqualTo("MEMORY");
        assertThat(m.driverName()).isEqualTo("H2 JDBC Driver");
        assertThat(m.databaseProductVersion()).isNotBlank();
    }

    /** The record ends up in debug logs and error messages; the URL's credential must not travel with it. */
    @Test
    void toStringLeavesTheConnectionParamsOut() {
        DataSourceMetadata metadata = new DataSourceMetadata(
                "primary",
                "app",
                List.of(),
                "orders",
                DatabaseProduct.POSTGRESQL,
                Map.of("password", new JdbcProperty(PropertySource.QUERY, "s3cret")),
                "PostgreSQL",
                "16",
                "PostgreSQL JDBC Driver");

        assertThat(metadata.toString())
                .contains("primary")
                .contains("orders")
                .doesNotContain("s3cret")
                .doesNotContain("password");
    }

    @Test
    void returnsEmptyWhenConnectionFails() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("db down"));

        try (LogCapture capture = LogCapture.attach(DataSourceMetadata.class)) {
            Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("broken", failing);

            assertThat(metadata).isEmpty();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'broken': db down");
            });
        }
    }

    @Test
    void returnsEmptyWhenMetadataExtractionThrowsRuntimeException() throws SQLException {
        // e.g. JdbcUrlParser choking on an exotic URL must not crash startup
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new IllegalStateException("unparseable"));

        try (LogCapture capture = LogCapture.attach(DataSourceMetadata.class)) {
            Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("exotic", failing);

            assertThat(metadata).isEmpty();
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'exotic': unparseable");
            });
        }
    }
}
