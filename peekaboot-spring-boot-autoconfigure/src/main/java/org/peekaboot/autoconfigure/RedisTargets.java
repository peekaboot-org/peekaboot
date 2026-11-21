package org.peekaboot.autoconfigure;

import java.util.Collection;
import java.util.List;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;

/** The {@link RedisConnection}s that do not depend on the client library. */
final class RedisTargets {

    private RedisTargets() {}

    static RedisConnection standalone(String host, int port, int database) {
        return new RedisConnection(List.of(new HealthConnection.Host(host, port)), database);
    }

    static RedisConnection sentinel(RedisSentinelConfiguration configuration, int database) {
        return new RedisConnection(hosts(configuration.getSentinels()), database);
    }

    static RedisConnection cluster(RedisClusterConfiguration configuration) {
        return new RedisConnection(hosts(configuration.getClusterNodes()), null);
    }

    private static List<HealthConnection.Host> hosts(Collection<RedisNode> nodes) {
        return nodes.stream()
                .filter(node -> node.getHost() != null)
                .map(node -> new HealthConnection.Host(node.getHost(), node.getPort()))
                .toList();
    }
}
