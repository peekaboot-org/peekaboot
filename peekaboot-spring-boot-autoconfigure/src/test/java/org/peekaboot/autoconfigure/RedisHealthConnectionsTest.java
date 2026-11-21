package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.health.HealthConnection;
import org.peekaboot.backend.domain.health.HealthConnection.Host;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisSocketConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/** The factories are only constructed: nothing here starts one, so no connection is ever opened. */
class RedisHealthConnectionsTest {

    private static final RedisConnection STANDALONE = new RedisConnection(List.of(new Host("cache.internal", 6380)), 2);
    private static final RedisConnection SENTINELS =
            new RedisConnection(List.of(new Host("s1", 26379), new Host("s2", 26379)), 1);
    private static final RedisConnection CLUSTER =
            new RedisConnection(List.of(new Host("c1", 7000), new Host("c2", 7001)), null);

    @Test
    void describesAStandaloneLettuceFactoryWithItsDatabase() {
        assertThat(describe(new LettuceConnectionFactory(standalone()))).isEqualTo(STANDALONE);
    }

    @Test
    void describesAStandaloneJedisFactoryWithItsDatabase() {
        assertThat(describe(new JedisConnectionFactory(standalone()))).isEqualTo(STANDALONE);
    }

    @Test
    void describesTheSentinelsOfALettuceFactoryInsteadOfItsDefaultStandaloneServer() {
        assertThat(describe(new LettuceConnectionFactory(sentinel()))).isEqualTo(SENTINELS);
    }

    @Test
    void describesTheSentinelsOfAJedisFactoryInsteadOfItsDefaultStandaloneServer() {
        assertThat(describe(new JedisConnectionFactory(sentinel()))).isEqualTo(SENTINELS);
    }

    @Test
    void describesALettuceClusterWithoutADatabase() {
        assertThat(describe(new LettuceConnectionFactory(cluster()))).isEqualTo(CLUSTER);
    }

    @Test
    void describesAJedisClusterWithoutADatabase() {
        assertThat(describe(new JedisConnectionFactory(cluster()))).isEqualTo(CLUSTER);
    }

    @Test
    void aLettuceFactoryOnAUnixSocketHasNoHostToName() {
        Map<String, RedisConnectionFactory> factories =
                Map.of("socket", new LettuceConnectionFactory(new RedisSocketConfiguration("/tmp/r.sock")));

        assertThat(sourceOf(factories)).isEmpty();
    }

    @Test
    void aFactoryOfAnotherClientLibraryHasNoEntryForAReaderThatDoesNotKnowIt() {
        Map<String, RedisConnectionFactory> factories = Map.of("jedis", new JedisConnectionFactory(standalone()));

        assertThat(new RedisHealthConnections(factories, List.of(new LettuceRedisTargetReader())).connections())
                .isEmpty();
    }

    @Test
    void keysEachFactoryByItsBeanName() {
        Map<String, RedisConnectionFactory> factories = new LinkedHashMap<>();
        factories.put("cacheFactory", new LettuceConnectionFactory(standalone()));
        factories.put("sessionFactory", new JedisConnectionFactory(sentinel()));

        assertThat(sourceOf(factories))
                .containsExactly(Map.entry("cacheFactory", STANDALONE), Map.entry("sessionFactory", SENTINELS));
    }

    @Test
    void withoutFactoriesThereIsNothingToDescribe() {
        assertThat(sourceOf(Map.of())).isEmpty();
    }

    private static RedisConnection describe(RedisConnectionFactory factory) {
        Map<String, HealthConnection> connections = sourceOf(Map.of("redisConnectionFactory", factory));
        assertThat(connections).containsOnlyKeys("redisConnectionFactory");
        return (RedisConnection) connections.get("redisConnectionFactory");
    }

    private static Map<String, HealthConnection> sourceOf(Map<String, RedisConnectionFactory> factories) {
        return new RedisHealthConnections(
                        factories, List.of(new LettuceRedisTargetReader(), new JedisRedisTargetReader()))
                .connections();
    }

    private static RedisStandaloneConfiguration standalone() {
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration("cache.internal", 6380);
        configuration.setDatabase(2);
        return configuration;
    }

    private static RedisSentinelConfiguration sentinel() {
        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration()
                .master("mymaster")
                .sentinel("s1", 26379)
                .sentinel("s2", 26379);
        configuration.setDatabase(1);
        return configuration;
    }

    private static RedisClusterConfiguration cluster() {
        return new RedisClusterConfiguration().clusterNode("c1", 7000).clusterNode("c2", 7001);
    }
}
