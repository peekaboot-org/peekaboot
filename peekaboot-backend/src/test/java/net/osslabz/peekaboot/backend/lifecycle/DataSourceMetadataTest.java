package net.osslabz.peekaboot.backend.lifecycle;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.SQLException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataSourceMetadataTest {

    @Test
    void extractsMetadataFromWorkingDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test;DB_CLOSE_DELAY=-1");

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("primary", dataSource);

        assertThat(metadata).isPresent();
        assertThat(metadata.get().getDataSourceName()).isEqualTo("primary");
        assertThat(metadata.get().getDatabaseProductName()).isEqualTo("H2");
        assertThat(metadata.get().getUrl()).contains("metadata-test");
    }

    @Test
    void extractsRemainingMetadataFieldsFromWorkingDataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test-2;DB_CLOSE_DELAY=-1");

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("primary", dataSource);

        assertThat(metadata).isPresent();
        DataSourceMetadata m = metadata.get();
        assertThat(m.getUsername()).isEqualTo("");
        assertThat(m.getHosts()).isEmpty();
        assertThat(m.getDatabaseName()).isEqualTo("metadata-test-2");
        // JdbcUrlParser derives a MODE connection param from the h2 in-memory URL
        assertThat(m.getConnectionParams()).containsKey("MODE");
        assertThat(m.getConnectionParams().get("MODE").value()).isEqualTo("MEMORY");
        assertThat(m.getDriverName()).isEqualTo("H2 JDBC Driver");
        assertThat(m.getDriverVersion()).isNotBlank();
        assertThat(m.getDatabaseProductVersion()).isNotBlank();
    }

    @Test
    void returnsEmptyWhenConnectionFails() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("db down"));

        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("broken", failing);

            assertThat(metadata).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'broken': db down");
            });
        } finally {
            detachListAppender(appender);
        }
    }

    @Test
    void returnsEmptyWhenMetadataExtractionThrowsRuntimeException() throws SQLException {
        // e.g. JdbcUrlParser choking on an exotic URL must not crash startup
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new IllegalStateException("unparseable"));

        ListAppender<ILoggingEvent> appender = attachListAppender();
        try {
            Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("exotic", failing);

            assertThat(metadata).isEmpty();
            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'exotic': unparseable");
            });
        } finally {
            detachListAppender(appender);
        }
    }

    /**
     * Captures {@link DataSourceMetadata}'s WARN log instead of letting it reach the
     * console; the negative-path scenarios below assert on the captured event.
     */
    private static ListAppender<ILoggingEvent> attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DataSourceMetadata.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return appender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(DataSourceMetadata.class);
        logger.detachAppender(appender);
        logger.setAdditive(true);
    }
}
