package org.peekaboot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Reads a {@link LettuceConnectionFactory}'s configuration. A unix domain socket has no host to name.
 * A factory in static master/replica mode cannot be told apart through public API and is reported
 * as its default localhost:6379.
 */
final class LettuceRedisTargetReader implements RedisTargetReader {

    @Override
    public @Nullable RedisConnection read(RedisConnectionFactory factory) {
        if (!(factory instanceof LettuceConnectionFactory lettuce)) {
            return null;
        }
        RedisSentinelConfiguration sentinel = lettuce.getSentinelConfiguration();
        if (sentinel != null) {
            return RedisTargets.sentinel(sentinel, lettuce.getDatabase());
        }
        RedisClusterConfiguration cluster = lettuce.getClusterConfiguration();
        if (cluster != null) {
            return RedisTargets.cluster(cluster);
        }
        if (lettuce.getSocketConfiguration() != null) {
            return null;
        }
        return RedisTargets.standalone(lettuce.getHostName(), lettuce.getPort(), lettuce.getDatabase());
    }
}
