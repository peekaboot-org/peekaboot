package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class DataSourceMetadataTest {

    @Test
    void extractsMetadataFromWorkingDataSource() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test;DB_CLOSE_DELAY=-1");

        DataSourceMetadata metadata = DataSourceMetadata.read("primary", dataSource);

        assertThat(metadata.dataSourceName()).isEqualTo("primary");
        assertThat(metadata.databaseProductName()).isEqualTo("H2");
        assertThat(metadata.databaseProduct()).isEqualTo(DatabaseProduct.H2);
    }

    @Test
    void extractsConnectionDetailsAndDriverFromWorkingDataSource() throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:metadata-test-2;DB_CLOSE_DELAY=-1");

        DataSourceMetadata m = DataSourceMetadata.read("primary", dataSource);

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
}
