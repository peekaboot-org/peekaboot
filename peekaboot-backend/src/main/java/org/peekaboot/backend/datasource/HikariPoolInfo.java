package org.peekaboot.backend.datasource;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Reads a Hikari pool's sizing for the ready banner and its configured JDBC URL for the health
 * endpoint. The only backend class that names a HikariCP type: PeekabootLifecycleAutoConfiguration
 * creates it under {@code @ConditionalOnClass(HikariDataSource)} only, so an application on another
 * pool never loads it and {@code ApplicationReadyListener} stays free of Hikari references.
 */
public final class HikariPoolInfo {

    /** The pool settings the banner reports. */
    public record PoolSettings(int minimumIdle, int maximumPoolSize, long connectionTimeoutMs) {}

    /** Empty for a DataSource that is not a Hikari pool. */
    // CloseResource: the pool is the application's own Spring bean, merely borrowed here
    // to read its settings - closing it would break the app
    @SuppressWarnings("PMD.CloseResource")
    public Optional<PoolSettings> settingsOf(DataSource dataSource) {
        if (!(dataSource instanceof HikariDataSource hikariDataSource)) {
            return Optional.empty();
        }
        HikariConfigMXBean config = hikariDataSource.getHikariConfigMXBean();
        return Optional.of(
                new PoolSettings(config.getMinimumIdle(), config.getMaximumPoolSize(), config.getConnectionTimeout()));
    }

    /**
     * The pool's configured JDBC URL, through any proxy that wraps it. Empty for a DataSource that is
     * no Hikari pool, has no URL, or fails to unwrap. Never opens a connection.
     */
    public Optional<String> jdbcUrlOf(DataSource dataSource) {
        try {
            // DataSource is a java.sql.Wrapper: this sees through proxies that delegate unwrap
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return Optional.ofNullable(
                        dataSource.unwrap(HikariDataSource.class).getJdbcUrl());
            }
        } catch (SQLException | RuntimeException e) {
            // a routing DataSource without a default target throws outside a routing context
            return Optional.empty();
        }
        return Optional.empty();
    }
}
