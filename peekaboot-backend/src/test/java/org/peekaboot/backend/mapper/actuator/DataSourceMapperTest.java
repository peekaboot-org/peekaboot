package org.peekaboot.backend.mapper.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import net.osslabz.jdbc.DatabaseProduct;
import net.osslabz.jdbc.JdbcProperty;
import net.osslabz.jdbc.PropertySource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.domain.datasource.DataSourceInfo;
import org.peekaboot.backend.domain.health.HealthStatus;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.masking.MaskingEngine;

class DataSourceMapperTest {

    private final DataSourceMapper mapper = new DataSourceMapper(new MaskingEngine());

    @Test
    void map_shouldMaskSensitiveProperties() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getConnectionParams())
                .thenReturn(Map.of(
                        "user", new JdbcProperty(PropertySource.QUERY, "admin"),
                        "password", new JdbcProperty(PropertySource.QUERY, "secret123")));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null, false);

        assertThat(result.get(0).properties()).containsEntry("user", "admin");
        assertThat(result.get(0).properties()).containsEntry("password", "******");
    }

    @Test
    void map_shouldAggregateHealthStatus() {
        DataSourceMetadata metadata = mockMetadata("primaryDS");

        HealthResponse health =
                new HealthResponse("UP", Map.of("db", new HealthResponse.HealthComponent("UP", Map.of())));

        List<DataSourceInfo> result = mapper.map(List.of(metadata), health, false);
        assertThat(result.get(0).health()).isEqualTo(HealthStatus.UP);
    }

    /**
     * With two DataSources Spring's {@code db} contributor is a composite: one child per
     * DataSource bean, named after it. Each row must show its own status, not the composite's
     * aggregate - otherwise one DataSource being down marks both rows down.
     */
    @Test
    void map_shouldReadEachDataSourcesOwnStatusFromInsideACompositeDb() {
        HealthResponse health = new HealthResponse(
                "DOWN",
                Map.of(
                        "db",
                        new HealthResponse.HealthComponent(
                                "DOWN",
                                null,
                                Map.of(
                                        "primary", new HealthResponse.HealthComponent("UP", Map.of()),
                                        "reporting", new HealthResponse.HealthComponent("DOWN", Map.of())))));

        List<DataSourceInfo> result =
                mapper.map(List.of(mockMetadata("primary"), mockMetadata("reporting")), health, false);

        assertThat(result)
                .extracting(DataSourceInfo::name, DataSourceInfo::health)
                .containsExactly(tuple("primary", HealthStatus.UP), tuple("reporting", HealthStatus.DOWN));
    }

    /** A DataSource the composite does not know (a bean Spring's indicator skipped) gets the composite's status. */
    @Test
    void map_shouldFallBackToTheCompositesStatusForADataSourceWithoutItsOwnChild() {
        HealthResponse health = new HealthResponse(
                "UP",
                Map.of(
                        "db",
                        new HealthResponse.HealthComponent(
                                "UP", null, Map.of("primary", new HealthResponse.HealthComponent("UP", Map.of())))));

        List<DataSourceInfo> result = mapper.map(List.of(mockMetadata("other")), health, false);

        assertThat(result.get(0).health()).isEqualTo(HealthStatus.UP);
    }

    @Test
    void map_shouldHandleEmptyList() {
        List<DataSourceInfo> result = mapper.map(List.of(), null, false);
        assertThat(result).isEmpty();
    }

    @Test
    void map_shouldHandleNullList() {
        List<DataSourceInfo> result = mapper.map(null, null, false);
        assertThat(result).isEmpty();
    }

    /** The product comes from the parsed JDBC URL, which DataSourceMetadata already carries. */
    @Test
    void map_carriesTheDatabaseProductOfTheJdbcUrl() {
        DataSourceMetadata metadata = mockMetadata("ds");
        when(metadata.getDatabaseProduct()).thenReturn(DatabaseProduct.POSTGRESQL);

        List<DataSourceInfo> result = mapper.map(List.of(metadata), null, false);

        assertThat(result.get(0).databaseProduct()).isEqualTo(DatabaseProduct.POSTGRESQL);
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
    void map_shouldFilterNullMetadata() {
        DataSourceMetadata metadata = mockMetadata("ds");

        List<DataSourceMetadata> listWithNulls = new java.util.ArrayList<>();
        listWithNulls.add(null);
        listWithNulls.add(metadata);
        listWithNulls.add(null);

        List<DataSourceInfo> result = mapper.map(listWithNulls, null, false);
        assertThat(result).hasSize(1);
    }

    private DataSourceMetadata mockMetadata(String name) {
        DataSourceMetadata metadata = mock(DataSourceMetadata.class);
        when(metadata.getDataSourceName()).thenReturn(name);
        when(metadata.getHosts()).thenReturn(List.of());
        return metadata;
    }
}
