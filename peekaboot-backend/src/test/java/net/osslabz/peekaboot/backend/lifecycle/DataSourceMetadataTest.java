package net.osslabz.peekaboot.backend.lifecycle;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

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
    void returnsEmptyWhenConnectionFails() throws SQLException {
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new SQLException("db down"));

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("broken", failing);

        assertThat(metadata).isEmpty();
    }

    @Test
    void returnsEmptyWhenMetadataExtractionThrowsRuntimeException() throws SQLException {
        // e.g. JdbcUrlParser choking on an exotic URL must not crash startup
        DataSource failing = mock(DataSource.class);
        when(failing.getConnection()).thenThrow(new IllegalStateException("unparseable"));

        Optional<DataSourceMetadata> metadata = DataSourceMetadata.fromDataSource("exotic", failing);

        assertThat(metadata).isEmpty();
    }
}
