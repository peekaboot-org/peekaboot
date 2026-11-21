package org.peekaboot.autoconfigure;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.health.HealthConnectionSource;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Names the Redis server each {@link RedisConnectionFactory} is configured for, by bean name as
 * Boot's Redis health contributor does. A factory no reader knows gets no entry.
 */
final class RedisHealthConnections implements HealthConnectionSource {

    private final Map<String, RedisConnectionFactory> factories;
    private final List<RedisTargetReader> readers;

    RedisHealthConnections(Map<String, RedisConnectionFactory> factories, List<RedisTargetReader> readers) {
        this.factories = factories;
        this.readers = readers;
    }

    @Override
    public String component() {
        return "redis";
    }

    @Override
    public Map<String, HealthConnection> connections() {
        Map<String, HealthConnection> connections = new LinkedHashMap<>();
        factories.forEach((beanName, factory) -> readers.stream()
                .map(reader -> reader.read(factory))
                .filter(Objects::nonNull)
                .findFirst()
                .ifPresent(connection -> connections.put(beanName, connection)));
        return connections;
    }
}
