package org.peekaboot.backend.domain.health;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The target of a DataSource.
 *
 * @param dataSource the DataSource bean's name
 * @param product the product the JDBC URL names, as a constant such as {@code POSTGRESQL}
 * @param instance the SQL Server instance the URL names
 */
public record DataSourceConnection(
        String dataSource,
        String product,
        List<HealthConnection.Host> hosts,
        @Nullable String database,
        @Nullable String instance)
        implements HealthConnection {}
