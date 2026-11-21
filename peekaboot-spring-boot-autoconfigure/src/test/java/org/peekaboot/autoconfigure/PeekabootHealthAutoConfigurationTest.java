package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.peekaboot.backend.controller.HealthController;
import org.peekaboot.backend.datasource.HikariPoolInfo;
import org.peekaboot.backend.domain.health.DataSourceConnection;
import org.peekaboot.backend.domain.health.HealthConnection.Host;
import org.peekaboot.backend.domain.health.MailConnection;
import org.peekaboot.backend.domain.health.RedisConnection;
import org.peekaboot.backend.health.HealthReportService;
import org.peekaboot.backend.health.HealthStatusCodes;
import org.peekaboot.backend.insights.InsightsDataReader;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointAutoConfiguration;
import org.springframework.boot.health.autoconfigure.contributor.HealthContributorAutoConfiguration;
import org.springframework.boot.health.autoconfigure.registry.HealthContributorRegistryAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.health.DataSourceHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailHealthContributorAutoConfiguration;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.mail.health.MailHealthIndicator;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.webmvc.autoconfigure.DispatcherServletAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class PeekabootHealthAutoConfigurationTest {

    private static final String H2_URL = "spring.datasource.url=jdbc:h2:mem:healthdb";

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootHealthAutoConfiguration.class,
                    PeekabootAutoConfiguration.class,
                    ActuatorSourcesAutoConfiguration.class,
                    PeekabootPathsAutoConfiguration.class,
                    DispatcherServletAutoConfiguration.class,
                    HealthContributorAutoConfiguration.class,
                    HealthContributorRegistryAutoConfiguration.class,
                    HealthEndpointAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @ParameterizedTest
    @CsvSource({"UP, 200", "UNKNOWN, 200", "DEGRADED, 200", "DOWN, 503", "OUT_OF_SERVICE, 503"})
    void mapsStatusesAsTheApplicationsHealthEndpointDoes(String status, int code) {
        contextRunner.run(context ->
                assertThat(context.getBean(HealthStatusCodes.class).of(status)).isEqualTo(code));
    }

    @Test
    void honoursTheApplicationsHttpMapping() {
        contextRunner
                .withPropertyValues(
                        "management.endpoint.health.status.http-mapping.down=500",
                        "management.endpoint.health.status.http-mapping.degraded=429")
                .run(context -> {
                    HealthStatusCodes codes = context.getBean(HealthStatusCodes.class);
                    assertThat(codes.of("DOWN")).isEqualTo(500);
                    assertThat(codes.of("DEGRADED")).isEqualTo(429);
                });
    }

    @Test
    void fallsBackToBootsDefaultsWithoutAHealthEndpoint() {
        contextRunner
                .withPropertyValues("management.endpoint.health.access=NONE")
                .run(context -> {
                    assertThat(context.getBean(HealthStatusCodes.class).of("DOWN"))
                            .isEqualTo(503);
                    HealthReportService.HealthAnswer answer =
                            context.getBean(HealthReportService.class).report();
                    assertThat(answer.report().status()).isEqualTo("UNKNOWN");
                    assertThat(answer.httpStatus()).isEqualTo(200);
                });
    }

    @Test
    void reportsTheApplicationsHealth() {
        contextRunner.withUserConfiguration(DownBrokerConfig.class).run(context -> {
            HealthReportService.HealthAnswer answer =
                    context.getBean(HealthReportService.class).report();
            assertThat(answer.httpStatus()).isEqualTo(503);
            assertThat(answer.report().components()).containsKey("broker");
        });
    }

    @Test
    void namesTheTargetOfAnAutoConfiguredDataSource() {
        withDataSource()
                .run(context -> assertThat(dbConnection(context.getBean(HealthReportService.class)))
                        .satisfies(connection -> {
                            assertThat(connection.product()).isEqualTo("H2");
                            assertThat(connection.database()).isEqualTo("healthdb");
                        }));
    }

    @Test
    void stillNamesTheDataSourceWhileTheLifecycleHistoryIsOff() {
        withDataSource()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootStorageAutoConfiguration.class, PeekabootLifecycleAutoConfiguration.class))
                .withPropertyValues("peekaboot.lifecycle.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HikariPoolInfo.class);
                    assertThat(dbConnection(context.getBean(HealthReportService.class))
                                    .database())
                            .isEqualTo("healthdb");
                });
    }

    @Test
    void windowIsLevelZerosSpan() {
        contextRunner.run(context -> assertThat(context.getBean(HealthReportService.class)
                        .report()
                        .report()
                        .windowMs())
                .isEqualTo(900_000));
        contextRunner
                .withPropertyValues(
                        "peekaboot.insights.levels[0].interval=250ms", "peekaboot.insights.levels[0].size=90")
                .run(context -> assertThat(context.getBean(HealthReportService.class)
                                .report()
                                .report()
                                .windowMs())
                        .isEqualTo(22_500));
    }

    @Test
    void answersWithTracingAndInsightsOff() {
        withFindingSources()
                .withPropertyValues("peekaboot.tracing.enabled=false", "peekaboot.insights.enabled=false")
                .run(context -> {
                    HealthReportService.HealthAnswer answer =
                            context.getBean(HealthReportService.class).report();
                    assertThat(answer.httpStatus()).isEqualTo(200);
                    assertThat(answer.report().traces()).isNull();
                    assertThat(answer.report().problems()).isEmpty();
                });
    }

    @Test
    void countsTracesWithTracingAndInsightsOn() {
        withFindingSources().run(context -> {
            assertThat(context.getBean(HealthReportService.class)
                            .report()
                            .report()
                            .traces())
                    .isNotNull();
            assertThat(context).hasSingleBean(InsightsDataReader.class);
        });
    }

    @Test
    void registersNothingWhilePeekabootIsOff() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HealthController.class));
    }

    @Test
    void anApplicationsOwnServiceReplacesPeekaboots() {
        contextRunner.withUserConfiguration(OwnServiceConfig.class).run(context -> {
            assertThat(context).hasSingleBean(HealthReportService.class).hasBean("myHealth");
            assertThat(context.getBean(HealthReportService.class)).isSameAs(OwnServiceConfig.SERVICE);
        });
    }

    @Test
    void describesTheServerOfTheApplicationsOwnRedisConnectionFactory() {
        contextRunner
                .withBean(
                        "cacheFactory",
                        RedisConnectionFactory.class,
                        () -> new LettuceConnectionFactory(new RedisStandaloneConfiguration("cache.internal", 6380)))
                .withBean(
                        "redisHealthIndicator",
                        HealthIndicator.class,
                        () -> () -> Health.up().build())
                .withPropertyValues("spring.data.redis.host=properties.example", "spring.data.redis.port=6379")
                .run(context -> {
                    assertThat(context).hasBean("peekabootRedisHealthConnections");
                    assertThat(context.getBean(HealthReportService.class)
                                    .report()
                                    .report()
                                    .components()
                                    .get("redis")
                                    .connection())
                            .isEqualTo(new RedisConnection(List.of(new Host("cache.internal", 6380)), 0));
                });
    }

    @Test
    void leavesRedisOutWithoutSpringDataRedis() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(RedisConnectionFactory.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("peekabootRedisHealthConnections"));
    }

    @Test
    void withoutTheLettuceClientOnlyTheJedisReaderIsRegistered() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("io.lettuce.core.RedisClient"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean("peekabootLettuceRedisTargetReader")
                        .hasBean("peekabootJedisRedisTargetReader")
                        .hasBean("peekabootRedisHealthConnections"));
    }

    @Test
    void withoutTheJedisClientOnlyTheLettuceReaderIsRegistered() {
        contextRunner
                .withClassLoader(new FilteredClassLoader("redis.clients.jedis.Jedis"))
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean("peekabootJedisRedisTargetReader")
                        .hasBean("peekabootLettuceRedisTargetReader")
                        .hasBean("peekabootRedisHealthConnections"));
    }

    @Test
    void leavesMailOutWithoutSpringsMailSender() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(JavaMailSenderImpl.class))
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean("peekabootMailHealthConnections"));
    }

    @Test
    void namesTheMailTargetInTheReport() {
        try (LogCapture mailWarnings = LogCapture.attach(MailHealthIndicator.class)) {
            contextRunner
                    .withConfiguration(AutoConfigurations.of(
                            MailSenderAutoConfiguration.class, MailHealthContributorAutoConfiguration.class))
                    .withPropertyValues("spring.mail.host=127.0.0.1", "spring.mail.port=1")
                    .run(context -> {
                        HealthReportService.HealthAnswer answer =
                                context.getBean(HealthReportService.class).report();
                        assertThat(answer.report().components().get("mail").connection())
                                .isEqualTo(new MailConnection("127.0.0.1", 1));
                        assertThat(answer.report().components().get("mail").status())
                                .isEqualTo("DOWN");
                    });
            assertThat(mailWarnings.appender().list)
                    .singleElement()
                    .satisfies(event -> assertThat(event.getFormattedMessage()).contains("Mail health check failed"));
        }
    }

    private WebApplicationContextRunner withDataSource() {
        return contextRunner
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class, DataSourceHealthContributorAutoConfiguration.class))
                .withPropertyValues(H2_URL);
    }

    private WebApplicationContextRunner withFindingSources() {
        return contextRunner
                .withConfiguration(AutoConfigurations.of(
                        PeekabootStorageAutoConfiguration.class,
                        PeekabootTracingAutoConfiguration.class,
                        InsightsAutoConfiguration.class))
                .withBean(SimpleMeterRegistry.class);
    }

    private static DataSourceConnection dbConnection(HealthReportService service) {
        return (DataSourceConnection)
                service.report().report().components().get("db").connection();
    }

    @Configuration(proxyBeanMethods = false)
    static class DownBrokerConfig {

        @Bean
        HealthIndicator brokerHealthIndicator() {
            return () -> Health.down().build();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class OwnServiceConfig {

        static final HealthReportService SERVICE = new HealthReportService(null, null, null, null, code -> 200, false);

        @Bean
        HealthReportService myHealth() {
            return SERVICE;
        }
    }
}
