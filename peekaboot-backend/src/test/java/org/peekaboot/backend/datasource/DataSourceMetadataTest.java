package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.datasource.ReportingDataSources.reporting;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.Host;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;

class DataSourceMetadataTest {

    private static final List<String> URLS_THE_GUARD_REFUSES = List.of(
            "jdbc:mysql://db/x;password=S3Cx",
            "jdbc:postgresql://db/shop:password=S3Cx",
            "jdbc:oracle:thin:@//db:1521/svc S3Cx",
            "jdbc:sqlserver://db;databaseName={x;password=S3Cy}",
            "jdbc:sqlserver://db;databaseName=shop;password=a&databaseName=S3Cx",
            "jdbc:h2:tcp://db/x?password=S3Cx",
            "jdbc:derby://db/x password=S3Cx",
            "jdbc:oracle:thin:@//db:1521/svc/a^b",
            "jdbc:oracle:thin:@//db:1521/svc/in st",
            "jdbc:oracle:thin:@ZUSR S3Cx:1521/svc");

    private static final List<String> URLS_THE_GUARD_DESCRIBES = List.of(
            "jdbc:mysql://ZUSR:S3Cx@db:3306/shop",
            "jdbc:postgresql://pg.internal:5432/thunderlytics",
            "jdbc:mysql://a:3306,b:3307/inv",
            "jdbc:sqlserver://db\\SQLEXPRESS:1433;databaseName=x",
            "jdbc:oracle:thin:@//db:1521/svc/inst",
            "jdbc:h2:mem:orders");

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

    @Test
    void aUserAndPasswordBeforeTheHostStayOffTheMetadata() throws SQLException {
        DataSourceMetadata m = DataSourceMetadata.read("primary", reporting("jdbc:mysql://ZUSR:S3Cx@db:3306/shop"));

        assertThat(m.hosts()).containsExactly(Host.of("db", 3306));
        assertThat(m.databaseName()).isEqualTo("shop");
        assertThat(m.connectionParams().values()).noneMatch(p -> p.source() == PropertySource.AUTHORITY);
        assertThat(m.connectionParams()).doesNotContainKeys("user", "password");
    }

    @ParameterizedTest
    @MethodSource("urlsTheGuardRefuses")
    void aTargetTheGuardRefusesIsHidden(String url) throws SQLException {
        DataSourceMetadata m = DataSourceMetadata.read("primary", reporting(url));

        assertThat(m.hosts()).isEmpty();
        assertThat(m.databaseName()).isNull();
    }

    @ParameterizedTest
    @MethodSource("everyUrlOfTheseTests")
    void theDashboardNamesNoMoreThanTheHealthReport(String url) throws SQLException {
        DataSourceMetadata m = DataSourceMetadata.read("ds", reporting(url));
        HealthConnection health = new DataSourceConnections(
                        Map.of("ds", new NeverConnectsDataSource()), dataSource -> Optional.of(url))
                .connections()
                .get("ds");

        if (health instanceof DataSourceConnection connection) {
            assertThat(m.hosts().stream()
                            .map(host -> new HealthConnection.Host(host.hostname(), host.port()))
                            .toList())
                    .isEqualTo(connection.hosts());
            assertThat(m.databaseName()).isEqualTo(connection.database());
        } else {
            assertThat(health).isNull();
            assertThat(m.hosts()).isEmpty();
            assertThat(m.databaseName()).isNull();
        }
    }

    static Stream<String> urlsTheGuardRefuses() {
        return URLS_THE_GUARD_REFUSES.stream();
    }

    static Stream<String> everyUrlOfTheseTests() {
        return Stream.concat(URLS_THE_GUARD_REFUSES.stream(), URLS_THE_GUARD_DESCRIBES.stream());
    }
}
