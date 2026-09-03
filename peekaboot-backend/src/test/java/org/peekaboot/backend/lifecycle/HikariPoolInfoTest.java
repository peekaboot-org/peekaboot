package org.peekaboot.backend.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class HikariPoolInfoTest {

    private final HikariPoolInfo poolInfo = new HikariPoolInfo();

    @Test
    void settingsOf_readsAHikariPoolsSizingAndTimeout() {
        // an unsealed HikariDataSource answers its config MXBean without ever opening the pool
        try (HikariDataSource hikari = new HikariDataSource()) {
            hikari.setMinimumIdle(3);
            hikari.setMaximumPoolSize(7);
            hikari.setConnectionTimeout(2500);

            assertThat(poolInfo.settingsOf(hikari)).contains(new HikariPoolInfo.PoolSettings(3, 7, 2500));
        }
    }

    @Test
    void settingsOf_isEmptyForAnyOtherDataSource() {
        assertThat(poolInfo.settingsOf(mock(DataSource.class))).isEmpty();
    }
}
