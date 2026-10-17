package org.peekaboot.testingapp.inventory;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/**
 * Writes the product cache synchronously. On Lettuce the default write is a reactive {@code SET},
 * which finds no parent span and would land in a trace of its own instead of the request's.
 */
@Configuration(proxyBeanMethods = false)
class ProductCacheConfiguration {

    @Bean
    RedisCacheManagerBuilderCustomizer immediateCacheWrites(RedisConnectionFactory connectionFactory) {

        return builder -> builder.cacheWriter(RedisCacheWriter.create(
                connectionFactory, RedisCacheWriter.RedisCacheWriterConfigurer::immediateWrites));
    }
}
