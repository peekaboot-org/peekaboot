package org.peekaboot.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;

/** Reads a {@link JedisConnectionFactory}'s configuration. */
final class JedisRedisTargetReader implements RedisTargetReader {

    @Override
    public @Nullable RedisConnection read(RedisConnectionFactory factory) {
        if (!(factory instanceof JedisConnectionFactory jedis)) {
            return null;
        }
        RedisSentinelConfiguration sentinel = jedis.getSentinelConfiguration();
        if (sentinel != null) {
            return RedisTargets.sentinel(sentinel, jedis.getDatabase());
        }
        RedisClusterConfiguration cluster = jedis.getClusterConfiguration();
        if (cluster != null) {
            return RedisTargets.cluster(cluster);
        }
        return RedisTargets.standalone(jedis.getHostName(), jedis.getPort(), jedis.getDatabase());
    }
}
