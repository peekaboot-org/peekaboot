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
    @CsvSource({
        "jdbc:mysql://ZUSR:S3Cx@db:3306/shop, MYSQL, db, 3306, shop",
        "jdbc:postgresql://ZUSR:S3Cx@pg/shop, POSTGRESQL, pg, 5432, shop",
        "jdbc:sqlserver://ZUSR:S3Cx@db;databaseName=shop, SQLSERVER, db, 1433, shop",
        "jdbc:mysql:// ZUSR:S3Cx@db/shop, MYSQL, db, 3306, shop",
        "jdbc:mysql://ZUSR:S3C)x@db/shop, MYSQL, db, 3306, shop",
        "jdbc:mysql://ZUSR:S3C:x@db/shop, MYSQL, db, 3306, shop",
        "jdbc:postgresql://ZUSR%40corp:S3C%40x@db/shop, POSTGRESQL, db, 5432, shop",
        "jdbc:mysql://ZUSR:S3C%2Fx%3Fy@db/shop, MYSQL, db, 3306, shop",
        "jdbc:postgresql://ZUSR@db/shop, POSTGRESQL, db, 5432, shop",
        "jdbc:mysql://ZUSR@db, MYSQL, db, 3306, ''",
        "jdbc:mysql://ZUSR:S3Cx@[::1]:3306/shop, MYSQL, [::1], 3306, shop",
        "jdbc:postgresql://ZUSR:S3Cx@[2001:db8::1]/shop, POSTGRESQL, [2001:db8::1], 5432, shop",
        "jdbc:oracle:thin:@//ZUSR:S3Cx@db:1521/svc, ORACLE, db, 1521, svc"
    })
    void aUserInfoInsideTheFirstHostIsCutOff(String url, String product, String hostname, int port, String database) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", product, List.of(new HealthConnection.Host(hostname, port)), database, null));
    }

    @ParameterizedTest
    @CsvSource({
        "'jdbc:mysql://ZUSR:S3Cx@db1:3306,db2:3307/shop', db1, 3306, db2, 3307",
        "'jdbc:mysql://ZUSR:S3Cx@:3306,real:3307/shop', localhost, 3306, real, 3307",
        "'jdbc:mysql:loadbalance://ZUSR:S3Cx@db1,db2/shop', db1, 3306, db2, 3306"
    })
    void aUserInfoBeforeTheFirstOfSeveralHostsIsCutOff(
            String url, String firstHostname, int firstPort, String secondHostname, int secondPort) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource",
                        "MYSQL",
                        List.of(
                                new HealthConnection.Host(firstHostname, firstPort),
                                new HealthConnection.Host(secondHostname, secondPort)),
                        "shop",
                        null));
    }

    @Test
    void aUserInfoBeforeNoHostNamesTheDefaultHost() {
        assertThat(connectionsOf("jdbc:mysql://ZUSR:S3Cx@/shop").get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "MYSQL", List.of(new HealthConnection.Host("localhost", 3306)), "shop", null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://ZUSR:S3C/x@db/shop",
                "jdbc:mysql://ZUSR:S3C?x@db/shop",
                "jdbc:mysql://ZUSR:S3C#x@db/shop",
                "jdbc:mysql://ZUSR:S3C;x@db/shop",
                "jdbc:mysql://ZUSR:S3C,x@db/shop",
                "jdbc:mysql://ZUSR:S3C(x@db/shop",
                "jdbc:mysql://ZUSR:S3C@x@db/shop",
                "jdbc:mysql://ZUSR:4242/S3C@db/shop",
                "jdbc:mysql://ZUSR:4242?S3C@db/shop",
                "jdbc:mysql://ZUSR:S3C@db@evil/shop",
                "jdbc:mysql://db1:3306,ZUSR:S3Cx@db2:3307/shop",
                "jdbc:mysql://db/shop?user=ZUSR@corp",
                "jdbc:mysql://db/shop?password=S3C@x",
                "jdbc:mysql://ZUSR:S3Cx@db/shop?password=S3C@y",
                "jdbc:postgresql://ZUSR:S3Cx@db/shop?user=ZUSR@corp",
                "jdbc:mysql://ZUSR:S3Cx@db/S3C@y",
                "jdbc:mysql://address=(host=db)(user=ZUSR)(password=S3C@x)/shop",
                "jdbc:mysql://(host=db,user=ZUSR,password=S3C@x)/shop",
                "jdbc:sqlserver://db;user=ZUSR@corp;password=S3Cx",
                "jdbc:sqlserver://ZUSR:S3Cx@db;databaseName=x;password={a;databaseName=S3Cy}",
                "jdbc:oracle:thin:@//ZUSR:S3Cx@db:1521/svc?password=S3Cy"
            })
    void aUrlWhoseUserInfoCannotBeCutOffCleanlyHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:oracle:thin:ZUSR/S3Cx@db:1521:ORCL, ORCL",
        "jdbc:oracle:thin:ZUSR/S3Cx@//db:1521/svc, svc",
        "jdbc:oracle:thin:ZUSR/S3Cx@db:1521/svc, svc",
        "jdbc:oracle:thin:\"ZUSR\"/\"S3C@x\"@db:1521/svc, svc"
    })
    void anOracleUserAndPasswordAreLeftOut(String url, String database) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "ORACLE", List.of(new HealthConnection.Host("db", 1521)), database, null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db/shop?password=a;instanceName=S3Cx",
                "jdbc:postgresql://db/shop?user=u&password=a;instanceName=S3Cx",
                "jdbc:mysql://db/shop?password=a;instanceName=S3Cx",
                "jdbc:mysql://db/shop?user=ZUSR;instanceName=ZUSR",
                "jdbc:mariadb://db/shop?password=a;instanceName=S3Cx",
                "jdbc:sqlite:/data/x.db?password=a;instanceName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;password=a&instanceName=S3Cx",
                "jdbc:sqlserver://db:1433;databaseName=shop;password=a&instanceName=S3Cx;encrypt=true",
                "jdbc:sqlserver://db;databaseName=shop;user=ZUSR&instanceName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;password=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;password=a& databaseName =S3Cx",
                "jdbc:sqlserver://db;password=a&database=S3Cx",
                "jdbc:h2:mem:x;PASSWORD=a&instanceName=S3Cx",
                "jdbc:h2:tcp://db/~/shop;PASSWORD=a&instanceName=S3Cx",
                "jdbc:h2:~/shop;USER=ZUSR&instanceName=ZUSR",
                "jdbc:derby:shop;password=a&instanceName=S3Cx",
                "jdbc:derby://db:1527/shop;password=a&instanceName=S3Cx",
                "jdbc:hsqldb:hsql://db/shop;password=a&instanceName=S3Cx",
                "jdbc:hsqldb:mem:x;password=a&instanceName=S3Cx",
                "jdbc:postgresql://db/shop?user=ZUSR&password=S3Cx",
                "jdbc:postgresql://db/shop?sslpassword=S3Cx",
                "jdbc:mysql://db/shop?password1=S3Cx",
                "jdbc:mysql://db/shop?trustCertificateKeyStorePassword=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;userName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;PWD=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;UID=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;keystoresecret=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;accesstoken=a&databaseName=S3Cx",
                "jdbc:h2:mem:x;USER=ZUSR;PASSWORD=S3Cx",
                "jdbc:oracle:thin:@//db:1521/svc?oracle.net.wallet_password=S3Cx",
                "jdbc:postgresql://db/shop?credentials=S3Cx",
                "jdbc:mysql://db/shop?apiKey=S3Cx"
            })
    void aUrlWithAUserOrSecretPropertyHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:mysql://db/shop?pass%77ord=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;p%61ssword=a&databaseName=S3Cx",
                "jdbc:postgresql://db/shop?options=-c%20search_path%3Dshop"
            })
    void aUrlWithAPercentEncodingAfterItsHostHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db))(CONNECT_DATA=(SID=x)))?password=(SERVICE_NAME=S3Cx)",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db))(CONNECT_DATA=(SID=x)))?password=(PORT=4242)",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db))(CONNECT_DATA=(SID=x)))?x=(SERVICE_NAME=S3Cx)",
                "jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db))(CONNECT_DATA=(SID=x))) (PORT=4242)"
            })
    void anOracleDescriptorFollowedByMoreTextHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:sqlserver://db;databaseName=shop;pa\u017F\u017Fword=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;PA\u017F\u017FWORD=a&databaseName=S3Cx",
                "jdbc:sqlserver://db:1433;pa\u017F\u017Fword=a&database=S3Cx;encrypt=true",
                "jdbc:sqlserver://db;databaseName=shop;pa\u017F\u017Fword=a&instanceName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;u\u017Fer=a&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;u\u017FerName=a&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;u\u017FerName=a&instanceName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;keyStore\u017Fecret=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;key\u017Ftore\u017Fecret=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;trustStorePa\u017F\u017Fword=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;clientKeyPa\u017F\u017Fword=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;AADSecurePrincipal\u017Fecret=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;aadSecurePrincipal\u017Fecret=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;keyVaultProviderClientKey=a&databaseName=S3Cx",
                "jdbc:sqlserver://db;databaseName=shop;AADSecurePrincipalId=a&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;keyStorePrincipalId=a&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;keyVaultProviderClientId=ZUSR&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;msiClientId=a&databaseName=ZUSR",
                "jdbc:sqlserver://db;databaseName=shop;applicationName=a&b"
            })
    void aSqlServerUrlWithAnAmpersandHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db/shop?pa\u017F\u017Fword=S3Cx",
                "jdbc:mysql://db/shop?u\u017Fer=ZUSR",
                "jdbc:h2:mem:shop;U\u017FER=ZUSR",
                "jdbc:postgresql://db/shop?ApplicationName=caf\u00E9"
            })
    void aUrlWithANonAsciiCharacterHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:sqlserver://db\\ZUSR@S3Cx;databaseName=shop",
                "jdbc:mysql://db\\ZUSR@S3Cx/shop",
                "jdbc:mysql://ZUSR:S3C\\x@db/shop"
            })
    void aUserInfoWithABackslashInItsHostIsNotCutOff(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @Test
    void aCredentialKeyAfterAParenthesisIsFound() {
        assertThat(connectionsOf("jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db)(PORT=1521))"
                        + "(CONNECT_DATA=(SERVICE_NAME=svc)(PASSWORD=S3Cx)))"))
                .isEmpty();
    }

    @Test
    void aCredentialKeyAfterACommaIsFound() {
        assertThat(connectionsOf("jdbc:postgresql://db/shop?options=a,password=S3Cx"))
                .isEmpty();
    }

    @Test
    void aCredentialKeyAfterWhitespaceIsFound() {
        assertThat(connectionsOf("jdbc:postgresql://db/shop?sslmode=require password=S3Cx"))
                .isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:postgresql://db/shop?sslmode=require&ApplicationName=shop-app, POSTGRESQL, 5432",
        "jdbc:mysql://db/shop?useSSL=true;serverTimezone=UTC, MYSQL, 3306",
        "jdbc:sqlserver://db;databaseName=shop;encrypt=true;trustServerCertificate=false, SQLSERVER, 1433"
    })
    void aUrlWithOtherPropertiesIsDescribed(String url, String product, int port) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", product, List.of(new HealthConnection.Host("db", port)), "shop", null));
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:postgresql://db/shop?instanceName=S3Cx, POSTGRESQL, db, 5432",
        "jdbc:mysql://db/shop?instanceName=S3Cx, MYSQL, db, 3306"
    })
    void onlyASqlServerUrlNamesAnInstance(String url, String product, String hostname, int port) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", product, List.of(new HealthConnection.Host(hostname, port)), "shop", null));
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:oracle:thin:@//db:1521/svc, svc",
        "jdbc:oracle:thin:@db:1521/svc, svc",
        "jdbc:oracle:thin:@db:1521:ORCL, ORCL",
        "'jdbc:oracle:thin:@(DESCRIPTION=(ADDRESS=(PROTOCOL=TCP)(HOST=db)(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=svc)))', svc"
    })
    void describesAnOracleThinUrlByTheHostAfterItsAt(String url, String database) {
        assertThat(connectionsOf(url).get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "ORACLE", List.of(new HealthConnection.Host("db", 1521)), database, null));
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
                "jdbc:sqlserver://db;databaseName=S3C x",
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
                "jdbc:sqlserver://db;databaseName=x;instanceName=S3C x",
                "jdbc:sqlserver://db;databaseName=x;instanceName=S3C:x",
                "jdbc:sqlserver://db;databaseName=x;instanceName=S3C%x"
            })
    void anInstanceThatIsNoPlainNameHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:h2:./S3C//ZUSR@db/shop",
                "jdbc:h2:file:/data/S3C//ZUSR@shop",
                "jdbc:sqlite://ZUSR:S3Cx@/data/x.db",
                "jdbc:mysql://db/sh%6Fp",
                "jdbc:oracle:thin:@//db:1521/svc S3C"
            })
    void aNameTheUrlDoesNotSpellOutHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @Test
    void namesADatabaseTheDriverSpellsInAnotherCase() {
        assertThat(connectionsOf("jdbc:hsqldb:hsql://db/Shop").get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "HSQLDB", List.of(new HealthConnection.Host("db", 9001)), "shop", null));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:sqlserver://db;databaseName=x;password={a;databaseName=S3Cx;z}",
                "jdbc:sqlserver://db;databaseName=x;password={;databaseName=S3Cx;}",
                "jdbc:sqlserver://db;databaseName=x;password={a;instanceName=S3Cx;z}",
                "jdbc:sqlserver://db;databaseName=x;user={ZUSR;instanceName=S3Cx;}",
                "jdbc:sqlserver://db;databaseName=x;password={a;databaseName=S3Cx}",
                "jdbc:sqlserver://db;databaseName={shop;S3Cx}",
                "jdbc:sqlserver://db;databaseName=shop;password={S3C;x}",
                "jdbc:sqlserver://db;databaseName=x;applicationName={a;databaseName=S3Cx;z}"
            })
    void aUrlWithABracedValueHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "jdbc:mysql://db/my$shop-2.0, my$shop-2.0",
        "jdbc:oracle:thin:@//db:1521/orcl.example.com, orcl.example.com",
        "jdbc:sqlserver://db;databaseName=Shop_DB, Shop_DB",
        "jdbc:h2:file:/data/shop, /data/shop",
        "jdbc:h2:./data/shop, ./data/shop",
        "jdbc:h2:~/shop, ~/shop",
        "jdbc:sqlite:/data/shop.db, /data/shop.db"
    })
    void namesAPlainDatabaseNameOrFilePath(String url, String database) {
        assertThat(((DataSourceConnection) connectionsOf(url).get("dataSource")).database())
                .isEqualTo(database);
    }

    @Test
    void namesTheHomeDirectoryDatabaseOfAnH2Server() {
        assertThat(connectionsOf("jdbc:h2:tcp://localhost:9092/~/shop").get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "H2", List.of(new HealthConnection.Host("localhost", 9092)), "~/shop", null));
    }

    @Test
    void aUrlWithoutADatabaseNamesAnEmptyOne() {
        assertThat(connectionsOf("jdbc:postgresql://db/").get("dataSource"))
                .isEqualTo(new DataSourceConnection(
                        "dataSource", "POSTGRESQL", List.of(new HealthConnection.Host("db", 5432)), "", null));
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

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://db",
                "jdbc:SQLSERVER://db;databaseName=shop",
                "jdbc:oracle:thin:ZUSR/S3C//x@db:1521/svc"
            })
    void aUrlTheDriverRejectsHasNoConnection(String url) {
        assertThat(connectionsOf(url)).isEmpty();
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
