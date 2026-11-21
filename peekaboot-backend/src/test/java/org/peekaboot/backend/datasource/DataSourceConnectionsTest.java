package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
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

    @Test
    void aHostnameLosesTheCredentialsTheUrlCarriesBeforeIt() {
        Map<String, HealthConnection> connections = connectionsOf("jdbc:mysql://root:pw@db:3306/shop");

        assertThat(((DataSourceConnection) connections.get("dataSource")).hosts())
                .containsExactly(new HealthConnection.Host("db", 3306));
        assertThat(connections.toString()).doesNotContain("root").doesNotContain("pw");
    }

    @Test
    void aHostWithoutAHostnameIsLeftOut() {
        DataSourceConnection connection = (DataSourceConnection)
                connectionsOf("jdbc:mysql://u:p@:3306,real:3307/shop").get("dataSource");

        assertThat(connection.hosts()).containsExactly(new HealthConnection.Host("real", 3307));
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
