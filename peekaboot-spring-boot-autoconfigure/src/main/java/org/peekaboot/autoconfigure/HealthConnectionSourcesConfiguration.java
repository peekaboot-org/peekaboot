package org.peekaboot.autoconfigure;

import org.peekaboot.backend.health.HealthConnectionSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/** The sources that say what each kind of health component connects to. */
@Configuration(proxyBeanMethods = false)
@Import(DataSourceHealthConnectionsConfiguration.class)
// every member is static, but Spring instantiates a configuration class whatever it holds
@SuppressWarnings("PMD.InstantiableUtilityClass")
class HealthConnectionSourcesConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.connection.RedisConnectionFactory")
    static class Redis {

        @Bean
        @ConditionalOnMissingBean(name = "peekabootRedisHealthConnections")
        public HealthConnectionSource peekabootRedisHealthConnections(
                ConfigurableListableBeanFactory beanFactory, ObjectProvider<RedisTargetReader> readers) {
            return new RedisHealthConnections(
                    ApplicationBeans.ofType(beanFactory, RedisConnectionFactory.class),
                    readers.orderedStream().toList());
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "io.lettuce.core.RedisClient")
        static class Lettuce {

            @Bean
            @ConditionalOnMissingBean(name = "peekabootLettuceRedisTargetReader")
            public RedisTargetReader peekabootLettuceRedisTargetReader() {
                return new LettuceRedisTargetReader();
            }
        }

        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "redis.clients.jedis.Jedis")
        static class Jedis {

            @Bean
            @ConditionalOnMissingBean(name = "peekabootJedisRedisTargetReader")
            public RedisTargetReader peekabootJedisRedisTargetReader() {
                return new JedisRedisTargetReader();
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.mail.javamail.JavaMailSenderImpl")
    static class Mail {

        @Bean
        @ConditionalOnMissingBean(name = "peekabootMailHealthConnections")
        public HealthConnectionSource peekabootMailHealthConnections(ConfigurableListableBeanFactory beanFactory) {
            return new MailHealthConnections(ApplicationBeans.ofType(beanFactory, JavaMailSenderImpl.class));
        }
    }
}
