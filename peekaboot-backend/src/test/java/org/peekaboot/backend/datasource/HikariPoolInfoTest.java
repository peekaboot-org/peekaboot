package org.peekaboot.backend.datasource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;

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
    void aDataSourceThatIsNoHikariPoolHasNoConnection() {
        assertThat(poolInfo.jdbcUrlOf(new NeverConnectsDataSource())).isEmpty();
    }
}
