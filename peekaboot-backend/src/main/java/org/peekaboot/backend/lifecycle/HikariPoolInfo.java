package org.peekaboot.backend.lifecycle;

import com.zaxxer.hikari.HikariConfigMXBean;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Reads a Hikari pool's sizing for the ready banner. The only lifecycle class that names a
 * HikariCP type: PeekabootLifecycleAutoConfiguration creates it under
 * {@code @ConditionalOnClass(HikariDataSource)} only, so an application on another pool
 * never loads it and {@link ApplicationReadyListener} stays free of Hikari references.
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
}
