package org.peekaboot.backend.domain.health;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The target of a Redis connection.
 *
 * @param database {@code null} for a cluster
 */
public record RedisConnection(
        List<HealthConnection.Host> hosts, @Nullable Integer database) implements HealthConnection {}
