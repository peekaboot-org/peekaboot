package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;

class DataSourceMapperTest {

    private final DataSourceMapper mapper = new DataSourceMapper();

    @Test
    void map_shouldMaskSensitiveProperties() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of(
                        "user", new JdbcProperty(PropertySource.QUERY, "admin"),
                        "password", new JdbcProperty(PropertySource.QUERY, "secret123")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);

        assertThat(result.get(0).properties()).containsEntry("user", "admin");
        assertThat(result.get(0).properties()).containsEntry("password", "******");
    }

    /**
     * The pre-engine regex was a bare substring match on "key", so any connection param
     * whose name merely contained "key" - not just the compound "api-key"/"apiKey" shape -
     * was masked too (e.g. "keyStore", "encryptionKeyPath"). The masking engine deliberately
     * drops bare "key" as a rule (it would otherwise mask server.ssl.key-store and
     * spring.jpa.key-generator elsewhere), so routing DataSourceMapper through the engine
     * narrows what gets masked here. This is the intended behaviour, not a regression -
     * pinned explicitly so a future reader sees it was a deliberate choice.
     */
    @Test
    void map_shouldNoLongerMaskConnectionParamsThatOnlyContainBareKey() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of("keyStore", new JdbcProperty(PropertySource.QUERY, "classpath:keystore.p12")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);

        assertThat(result.get(0).properties()).containsEntry("keyStore", "classpath:keystore.p12");
    }

    @Test
    void map_shouldAggregateHealthStatus() {
        DataSourceMetadata metadata = mockMetadata("primaryDS");

        HealthResponse health = new HealthResponse(
                new HealthResponse.HealthBody(
                        "UP", Map.of("db", new HealthResponse.HealthComponent("UP", Map.of())), List.of()),
                200);

        List<DataSourceInfo> result = mapper.map(List.of(metadata), health);
        assertThat(result.get(0).health()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void map_shouldHandleEmptyList() {
        List<DataSourceInfo> result = mapper.map(List.of(), null);
        assertThat(result).isEmpty();
    }

    @Test
    void map_shouldHandleNullList() {
        List<DataSourceInfo> result = mapper.map(null, null);
        assertThat(result).isEmpty();
    }

    @Test
    void map_shouldDetectDatabaseProduct() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL 15.1");

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);
        assertThat(result.get(0).databaseProduct().name()).isEqualTo("POSTGRESQL");
    }

    @Test
    void map_shouldMaskKeyAndTokenProperties() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of(
                        "apiKey", new JdbcProperty(PropertySource.QUERY, "myapikey"),
                        "authToken", new JdbcProperty(PropertySource.QUERY, "mytoken"),
                        "server", new JdbcProperty(PropertySource.QUERY, "localhost")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);

        assertThat(result.get(0).properties()).containsEntry("apiKey", "******");
        assertThat(result.get(0).properties()).containsEntry("authToken", "******");
        assertThat(result.get(0).properties()).containsEntry("server", "localhost");
    }

    @Test
    void map_shouldReturnRealValueWhenUnmaskIsTrue() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of("password", new JdbcProperty(PropertySource.QUERY, "secret123")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null, true);

        assertThat(result.get(0).properties()).containsEntry("password", "secret123");
    }

    @Test
    void map_shouldStillMaskWhenUnmaskIsFalse() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of("password", new JdbcProperty(PropertySource.QUERY, "secret123")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null, false);

        assertThat(result.get(0).properties()).containsEntry("password", "******");
    }

    @Test
    void map_shouldFilterNullMetadata() {
        DataSourceMetadata metadata = mockMetadata("ds");

        List<DataSourceMetadata> listWithNulls = new java.util.ArrayList<>();
        listWithNulls.add(null);
        listWithNulls.add(metadata);
        listWithNulls.add(null);

        List<DataSourceInfo> result = mapper.map(listWithNulls, null);
        assertThat(result).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource({
        "MySQL 8.0, MYSQL",
        "MariaDB 10.6, MARIADB",
        "H2, H2",
        "Oracle Database 19c, ORACLE",
        "Microsoft SQL Server 2019, SQLSERVER",
        "SQLite, SQLITE",
        "Apache Derby, DERBY",
        "HSQL Database Engine, HSQLDB",
        "SomeExoticDatabase, UNKNOWN"
    })
    void map_shouldDetectDatabaseProductForEachVendor(String productName, DatabaseProduct expected) {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getDatabaseProductName()).thenReturn(productName);

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);

        assertThat(result.get(0).databaseProduct()).isEqualTo(expected);
    }

    @Test
    void map_shouldDetectUnknownDatabaseProductWhenNameIsNull() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getDatabaseProductName()).thenReturn(null);

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null);

        assertThat(result.get(0).databaseProduct()).isEqualTo(DatabaseProduct.UNKNOWN);
    }

    private DataSourceMetadata mockMetadata(String name) {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn(name);
        when(metadata.getHosts()).thenReturn(List.of());
        return metadata;
    }
}
