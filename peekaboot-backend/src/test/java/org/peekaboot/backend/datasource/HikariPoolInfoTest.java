package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

class HikariPoolInfoTest {

    private final HikariPoolInfo poolInfo = new HikariPoolInfo();

    @Test
    void settingsOfReadsAHikariPoolsSizingAndTimeout() {
        // an unsealed HikariDataSource answers its config MXBean without ever opening the pool
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setMinimumIdle(3);
            hikari.setMaximumPoolSize(7);
            hikari.setConnectionTimeout(2500);

            assertThat(poolInfo.settingsOf(hikari)).contains(new HikariPoolInfo.PoolSettings(3, 7, 2500));
        }
    }

    @Test
    void settingsOfIsEmptyForAnyOtherDataSource() {
        assertThat(poolInfo.settingsOf(mock(DataSource.class))).isEmpty();
    }

    @Test
    void readsTheConfiguredUrlOfAPoolThatNeverStarted() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setJdbcUrl("jdbc:h2:mem:orders");

            assertThat(poolInfo.jdbcUrlOf(hikari)).contains("jdbc:h2:mem:orders");
        }
    }

    @Test
    void aHikariPoolBehindAProxyIsDescribed() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setJdbcUrl("jdbc:h2:mem:orders");

            assertThat(poolInfo.jdbcUrlOf(new DelegatingDataSource(hikari))).contains("jdbc:h2:mem:orders");
        }
    }

    @Test
    void aPoolWithoutAConfiguredUrlHasNone() {
        try (HikariDataSource hikari = new HikariDataSource()) {
            assertThat(poolInfo.jdbcUrlOf(hikari)).isEmpty();
        }
    }

    @Test
    void aRoutingDataSourceWithoutADefaultTargetHasNone() {
        assertThat(poolInfo.jdbcUrlOf(new TenantRoutingDataSource())).isEmpty();
    }

    @Test
    void aDataSourceThatIsNoHikariPoolHasNoConnection() {
        assertThat(poolInfo.jdbcUrlOf(new NeverConnectsDataSource())).isEmpty();
    }

    /** Routes by a tenant key that is unset outside a request, and has no default target. */
    static final class TenantRoutingDataSource extends AbstractRoutingDataSource {

        TenantRoutingDataSource() {
            setTargetDataSources(Map.of("tenant-a", new NeverConnectsDataSource()));
            afterPropertiesSet();
        }

        @Override
        protected @Nullable Object determineCurrentLookupKey() {
            return null;
        }
    }
}
