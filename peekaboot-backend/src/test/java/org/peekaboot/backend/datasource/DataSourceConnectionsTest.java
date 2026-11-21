package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.health.HealthConnectionSource;

class DataSourceConnectionsTest {

    private static Map<String, HealthConnection> connectionsOf(String url) {
        return connectionsOf(Optional.of(url));
    }

    private static Map<String, HealthConnection> connectionsOf(Optional<String> url) {
        return new DataSourceConnections(Map.of("dataSource", new NeverConnectsDataSource()), dataSource -> url)
                .connections();
    }

    @Test
    void describesAPostgresUrlByProductHostsAndDatabase() {
        assertThat(connectionsOf("jdbc:postgresql://pg.internal:5432/thunderlytics")
                        .get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource",
                        "POSTGRESQL",
                        List.of(new HealthConnection.Host("pg.internal", 5432)),
                        "thunderlytics",
                        null));
    }

    @Test
    void namesEveryHostOfAFailoverUrl() {
        DataSourceConnection connection = (DataSourceConnection)
                connectionsOf("jdbc:mysql://a:3306,b:3307/inv").get("dataSource");

        assertThat(connection.hosts())
                .containsExactly(new HealthConnection.Host("a", 3306), new HealthConnection.Host("b", 3307));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://root:pw@db:3306/shop",
                "jdbc:postgresql://u:p@pg/db",
                "jdbc:mysql://u:p@:3306,real:3307/shop",
                "jdbc:mysql://root:p/w@db/shop",
                "jdbc:mysql://root:p?w@db/shop",
                "jdbc:mysql://root:123/w@db/shop",
                "jdbc:mysql://root:123?w@db/shop"
            })
    void aUrlThatCarriesCredentialsBeforeItsHostHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://address=(host=db)(port=3306)(user=root)(password=secret)/shop",
                "jdbc:mysql://(host=db,user=root,password=secret)/shop",
                "jdbc:mysql://db:3306,(host=replica,password=secret)/shop"
            })
    void aUrlWithAHostThatIsNoPlainHostNameHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:postgresql://10.0.0.5:5432/db, 10.0.0.5",
        "jdbc:postgresql://[::1]:5432/db, [::1]",
        "jdbc:mysql://shop_db:5432/db, shop_db"
    })
    void namesAnIpLiteralOrPlainHostName(String url, String hostname) {
        assertThat(((DataSourceConnection) connectionsOf(url).get("dataSource")).hosts())
                .containsExactly(new HealthConnection.Host(hostname, 5432));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:oracle:thin:@//db:1521/svc?password=S3Cx",
                "jdbc:oracle:thin:@db:1521/svc?password=S3Cx",
                "jdbc:oracle:thin:@db:1521:ORCL?password=S3Cx",
                "jdbc:oracle:thin:@//db:1521/svc?oracle.jdbc.password=S3Cx",
                "jdbc:oracle:thin:@//db:1521/svc#S3Cx",
                "jdbc:oracle:thin:@//db:1521/svc S3Cx",
                "jdbc:sqlserver://db;databaseName=x;password={a;databaseName=S3Cx}",
                "jdbc:sqlserver://db;databaseName={shop;S3Cx}",
                "jdbc:mysql://db/x;password=S3Cx",
                "jdbc:mysql://db/x\\S3Cx",
                "jdbc:postgresql://db/ZUSR:S3Cx",
                "jdbc:postgresql://db/S3C%40x"
            })
    void aDatabaseThatIsNoPlainNameHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:sqlserver://db;databaseName=x;password={a;instanceName=S3Cx}",
                "jdbc:sqlserver://db;databaseName=x;instanceName={S3C x}"
            })
    void anInstanceThatIsNoPlainNameHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:mysql://db/my$shop-2.0, my$shop-2.0",
        "jdbc:oracle:thin:@//db:1521/orcl.example.com, orcl.example.com",
        "jdbc:sqlserver://db;databaseName=Shop_DB, Shop_DB",
        "jdbc:h2:file:/data/shop, /data/shop",
        "jdbc:h2:./data/shop, ./data/shop",
        "jdbc:sqlite:/data/shop.db, /data/shop.db"
    })
    void namesAPlainDatabaseNameOrFilePath(String url, String database) {
        assertThat(((DataSourceConnection) connectionsOf(url).get("dataSource")).database())
                .isEqualTo(database);
    }

    @Test
    void aUrlWithoutADatabaseNamesAnEmptyOne() {
        assertThat(connectionsOf("jdbc:postgresql://db").get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "POSTGRESQL", List.of(new HealthConnection.Host("db", null)), "", null));
    }

    @Test
    void namesTheSqlServerInstanceOfTheHostForm() {
        DataSourceConnection connection =
                (DataSourceConnection) connectionsOf("jdbc:sqlserver://db\\SQLEXPRESS:1433;databaseName=x")
                        .get("dataSource");

        assertThat(connection.instance()).isEqualTo("SQLEXPRESS");
        assertThat(connection.hosts()).containsExactly(new HealthConnection.Host("db", 1433));
    }

    @Test
    void aSqlServerInstanceWithoutAPortHasNoPort() {
        assertThat(connectionsOf("jdbc:sqlserver://db\\SQLEXPRESS;databaseName=x")
                        .get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "SQLSERVER", List.of(new HealthConnection.Host("db", null)), "x", "SQLEXPRESS"));
    }

    @Test
    void namesTheSqlServerInstanceOfThePropertyForm() {
        DataSourceConnection connection =
                (DataSourceConnection) connectionsOf("jdbc:sqlserver://db:1433;instanceName=SQLEXPRESS;databaseName=x")
                        .get("dataSource");

        assertThat(connection.instance()).isEqualTo("SQLEXPRESS");
    }

    @Test
    void anEmbeddedDatabaseHasNoHosts() {
        DataSourceConnection connection =
                (DataSourceConnection) connectionsOf("jdbc:h2:mem:orders").get("dataSource");

        assertThat(connection.product()).isEqualTo("H2");
        assertThat(connection.hosts()).isEmpty();
        assertThat(connection.database()).isEqualTo("orders");
    }

    @Test
    void anUnparseableUrlHasNoConnection() {
        assertThat(connectionsOf("garbage")).isEmpty();
    }

    @Test
    void aDataSourceWithoutAConfiguredUrlHasNoConnection() {
        assertThat(connectionsOf(Optional.empty())).isEmpty();
    }

    @Test
    void describesTheDbComponent() {
        HealthConnectionSource source = new DataSourceConnections(Map.of(), dataSource -> Optional.empty());

        assertThat(source.component()).isEqualTo("db");
    }
}
