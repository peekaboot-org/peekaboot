package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.qos.logback.classic.spi.ILoggingEvent;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import liquibase.UpdateSummaryOutputEnum;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.MapAssert;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.actuator.InsightsSource;
import org.peekaboot.backend.actuator.parsed.HealthResponse;
import org.peekaboot.backend.actuator.parsed.HealthResponse.ComponentDescriptor;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.boot.actuate.autoconfigure.info.InfoContributorAutoConfiguration;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesBeanDescriptor;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesDescriptor;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.env.EnvironmentEndpoint.EnvironmentDescriptor;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.actuate.info.JavaInfoContributor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.flyway.actuate.endpoint.FlywayEndpoint.FlywayBeansDescriptor;
import org.springframework.boot.health.actuate.endpoint.AdditionalHealthEndpointPath;
import org.springframework.boot.health.actuate.endpoint.CompositeHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroup;
import org.springframework.boot.health.actuate.endpoint.HealthEndpointGroups;
import org.springframework.boot.health.actuate.endpoint.HttpCodeStatusMapper;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.actuate.endpoint.StatusAggregator;
import org.springframework.boot.health.autoconfigure.actuate.endpoint.HealthEndpointProperties;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.DefaultReactiveHealthContributorRegistry;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.liquibase.actuate.endpoint.LiquibaseEndpoint.ChangeSetDescriptor;
import org.springframework.boot.liquibase.actuate.endpoint.LiquibaseEndpoint.LiquibaseBeansDescriptor;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import reactor.core.publisher.Mono;

class ActuatorSourcesAutoConfigurationTest {

    // java.util.logging is out of logback's reach; the strong reference keeps the level from being collected.
    private static final Logger LIQUIBASE_LOGGER = Logger.getLogger("liquibase");

    private static final HealthIndicator H2_UP =
            () -> Health.up().withDetail("database", "H2").build();

    private static Level liquibaseLevel;

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ActuatorSourcesAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @BeforeAll
    static void quietLiquibase() {
        liquibaseLevel = LIQUIBASE_LOGGER.getLevel();
        LIQUIBASE_LOGGER.setLevel(Level.WARNING);
    }

    @AfterAll
    static void restoreLiquibaseLogging() {
        LIQUIBASE_LOGGER.setLevel(liquibaseLevel);
    }

    /** The whole point of owning the endpoint: the application's setting must not reach it. */
    @Test
    void envSourceReadsRealValuesWhileTheApplicationHidesThemFromItsOwnActuator() {
        contextRunner
                .withPropertyValues("management.endpoint.env.show-values=never", "fixture.value=readable")
                .run(context -> {
                    Object descriptor = read(context, "env");

                    assertThat(descriptor)
                            .isInstanceOfSatisfying(
                                    EnvironmentDescriptor.class,
                                    env -> assertThat(env.getPropertySources())
                                            .anySatisfy(source -> assertThat(source.getProperties())
                                                    .extractingByKey("fixture.value")
                                                    .satisfies(value -> assertThat(value.getValue())
                                                            .isEqualTo("readable"))));
                });
    }

    @Test
    void configpropsSourceReadsRealValuesWhileTheApplicationHidesThemFromItsOwnActuator() {
        contextRunner
                .withUserConfiguration(FixturePropertiesConfig.class)
                .withPropertyValues("management.endpoint.configprops.show-values=never", "fixture.value=readable")
                .run(context -> {
                    Object descriptor = read(context, "configprops");

                    assertThat(descriptor).isInstanceOfSatisfying(ConfigurationPropertiesDescriptor.class, config -> {
                        ConfigurationPropertiesBeanDescriptor bean = config.getContexts().values().stream()
                                .flatMap(ctx -> ctx.getBeans().values().stream())
                                .filter(candidate -> "fixture".equals(candidate.getPrefix()))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("no bean with prefix 'fixture'"));
                        assertThat(bean.getProperties()).containsEntry("value", "readable");
                    });
                });
    }

    @Test
    void healthSourceWalksTheRegistryAndTimesEachCheck() {
        contextRunner
                .withUserConfiguration(HealthBeansConfig.class)
                .withBean("db", HealthIndicator.class, () -> H2_UP)
                .run(context -> assertThat(read(context, "health"))
                        .isInstanceOfSatisfying(HealthResponse.class, health -> {
                            assertThat(health.status()).isEqualTo("UP");
                            ComponentDescriptor db = health.components().get("db");
                            assertThat(db.details()).containsEntry("database", "H2");
                            assertThat(db.durationMs()).isNotNull();
                        }));
    }

    @Test
    void healthSourceBoundsAHungCheckByTheConfiguredTimeout() {
        CountDownLatch release = new CountDownLatch(1);
        contextRunner
                .withUserConfiguration(HealthBeansConfig.class)
                .withBean("hung", HealthIndicator.class, () -> () -> upOnceReleased(release))
                .withPropertyValues("peekaboot.health.timeout=50ms")
                .run(context -> {
                    LogCapture log = LogCapture.attach(HealthCheckRuns.class);
                    try {
                        assertThat(read(context, "health")).isInstanceOfSatisfying(HealthResponse.class, health -> {
                            assertThat(health.status()).isEqualTo("DOWN");
                            ComponentDescriptor hung = health.components().get("hung");
                            assertThat(hung.status()).isEqualTo("DOWN");
                            assertThat(hung.details()).isEqualTo(Map.of("error", "Timed out after 50ms"));
                        });
                        assertThat(messages(log))
                                .containsExactly("Peekaboot health: 'hung' did not answer within 50ms; reported DOWN");
                    } finally {
                        release.countDown();
                        try {
                            // a check that never went late logs nothing more, and the failure above stays visible
                            await().atMost(Duration.ofSeconds(5)).until(() -> {
                                List<String> lines = messages(log);
                                return lines.isEmpty()
                                        || lines.getLast().startsWith("Peekaboot health: 'hung' answered UP after ");
                            });
                        } finally {
                            log.close();
                        }
                    }
                });
    }

    @Test
    void healthSourceReadsReactiveContributorsToo() {
        contextRunner
                .withUserConfiguration(HealthBeansConfig.class)
                .withBean("db", HealthIndicator.class, () -> H2_UP)
                .withBean(DefaultReactiveHealthContributorRegistry.class, () -> {
                    DefaultReactiveHealthContributorRegistry reactive = new DefaultReactiveHealthContributorRegistry();
                    reactive.registerContributor("redis", (ReactiveHealthIndicator)
                            () -> Mono.just(Health.up().build()));
                    return reactive;
                })
                .run(context -> assertThat(read(context, "health"))
                        .isInstanceOfSatisfying(
                                HealthResponse.class,
                                health -> assertThat(health.components())
                                        .containsOnlyKeys("db", "redis")
                                        .extractingByKey("redis")
                                        .extracting(ComponentDescriptor::status)
                                        .isEqualTo("UP")));
    }

    /** The walk bypasses Boot's own evaluation, and with it Boot's slow-indicator WARN. */
    @Test
    void healthSourceHonoursBootsSlowIndicatorThreshold() {
        contextRunner
                .withUserConfiguration(HealthBeansConfig.class)
                .withBean("db", HealthIndicator.class, () -> H2_UP)
                .withPropertyValues("management.endpoint.health.logging.slow-indicator-threshold=0ms")
                .run(context -> {
                    try (LogCapture log = LogCapture.attach(HealthCheckRuns.class)) {
                        read(context, "health");

                        assertThat(messages(log))
                                .singleElement()
                                .asString()
                                .matches("Health contributor \\S+ \\(db\\) took \\d+ms to respond");
                    }
                });
    }

    @Test
    void healthSourceReadsNullWithTheRegistryButNoEndpoint() {
        contextRunner
                .withUserConfiguration(HealthRegistryConfig.class)
                .withBean("db", HealthIndicator.class, () -> H2_UP)
                .run(context -> assertThat(read(context, "health")).isNull());
    }

    /**
     * Answers like Spring's default group does for an anonymous caller of
     * {@code /actuator/health}: neither components nor details. A descriptor that still
     * carries them can only have come from the endpoint bean's own {@code health()}. With no
     * registry and no groups bean to walk, the source reads the endpoint bean, without a deadline.
     */
    @Test
    void withoutBootsHealthBeansTheSourceReadsTheEndpointBean() {
        contextRunner.withUserConfiguration(HealthEndpointConfig.class).run(context -> {
            Object descriptor = read(context, "health");

            assertThat(descriptor).isInstanceOfSatisfying(CompositeHealthDescriptor.class, health -> {
                assertThat(health.getStatus()).isEqualTo(Status.UP);
                assertThat(health.getComponents().get("db"))
                        .isInstanceOfSatisfying(
                                IndicatedHealthDescriptor.class,
                                db -> assertThat(db.getDetails()).containsEntry("database", "H2"));
            });
        });
    }

    @Test
    void healthSourceReadsNullWhenTheApplicationHasNoHealthEndpointBean() {
        contextRunner.run(context -> assertThat(read(context, "health")).isNull());
    }

    /**
     * {@code LoggingSystem} is a bean only because {@code LoggingApplicationListener} puts it
     * there during {@code SpringApplication} startup; {@link WebApplicationContextRunner} never
     * runs that listener, so this test supplies the stand-in every real Peekaboot-enabled
     * application already has, local to the one test that needs it - the shared
     * {@code contextRunner} deliberately leaves it absent so
     * {@link #loggersSourceReadsNullWhenTheApplicationHasNoLoggingSystemBean} stays meaningful.
     */
    @Test
    void loggersSourceReadsFromTheApplicationsLoggingSystem() {
        contextRunner
                .withBean(
                        LoggingSystem.class, () -> LoggingSystem.get(getClass().getClassLoader()))
                .run(context -> assertThat(read(context, "loggers")).isNotNull());
    }

    @Test
    void loggersSourceReadsNullWhenTheApplicationHasNoLoggingSystemBean() {
        contextRunner.run(context -> assertThat(read(context, "loggers")).isNull());
    }

    /** A real migration on an in-memory H2, so the reading is Liquibase's own history table. */
    @Test
    void liquibaseSourceReadsTheChangeSetsOfTheApplicationsLiquibaseBean() {
        contextRunner.withUserConfiguration(LiquibaseConfig.class).run(context -> {
            Object descriptor = read(context, "liquibase");

            assertThat(descriptor).isInstanceOfSatisfying(LiquibaseBeansDescriptor.class, liquibase -> {
                List<String> ids = liquibase.getContexts().values().stream()
                        .flatMap(ctx -> ctx.getLiquibaseBeans().values().stream())
                        .flatMap(bean -> bean.getChangeSets().stream())
                        .map(ChangeSetDescriptor::getId)
                        .toList();
                assertThat(ids).containsExactly("source-test-1");
            });
        });
    }

    @Test
    void liquibaseSourceReadsNullWhenTheApplicationHasNoLiquibaseBean() {
        contextRunner.run(context -> assertThat(read(context, "liquibase")).isNull());
    }

    /** Boot's own how-to for a second DataSource marks its migration bean {@code defaultCandidate = false}. */
    @Test
    void liquibaseSourceReadsALiquibaseBeanThatIsNoDefaultCandidate() {
        contextRunner
                .withUserConfiguration(NonDefaultCandidateLiquibaseConfig.class)
                .run(context -> {
                    Object descriptor = read(context, "liquibase");

                    assertThat(descriptor)
                            .isInstanceOfSatisfying(
                                    LiquibaseBeansDescriptor.class,
                                    liquibase -> assertThat(
                                                    liquibase.getContexts().values())
                                            .flatMap(ctx ->
                                                    ctx.getLiquibaseBeans().keySet())
                                            .containsExactly("secondaryLiquibase"));
                });
    }

    @Test
    void flywaySourceReadsAFlywayBeanThatIsNoDefaultCandidate() {
        contextRunner
                .withUserConfiguration(NonDefaultCandidateFlywayConfig.class)
                .run(context -> {
                    Object descriptor = read(context, "flyway");

                    assertThat(descriptor)
                            .isInstanceOfSatisfying(
                                    FlywayBeansDescriptor.class,
                                    flyway -> assertThat(flyway.getContexts().values())
                                            .flatMap(ctx -> ctx.getFlywayBeans().keySet())
                                            .containsExactly("secondaryFlyway"));
                });
    }

    @Test
    void flywaySourceReadsNullWhenTheApplicationHasNoFlywayBean() {
        contextRunner.run(context -> assertThat(read(context, "flyway")).isNull());
    }

    @Test
    void infoSourceCarriesJavaSystemAndProcessWithNoManagementInfoSetting() {
        contextRunner.run(context -> info(context).containsKeys("java", "os", "process"));
    }

    @Test
    void infoSourceCarriesTheApplicationsInfoProperties() {
        contextRunner
                .withPropertyValues("info.build.version=1.2.3")
                .run(context -> info(context).extractingByKey("build").isEqualTo(Map.of("version", "1.2.3")));
    }

    @Test
    void anApplicationContributorWinsOverAnInfoProperty() {
        contextRunner
                .withUserConfiguration(BuildContributorConfig.class)
                .withPropertyValues("info.build.version=from-property")
                .run(context -> info(context).extractingByKey("build").isEqualTo(Map.of("version", "from-bean")));
    }

    @Test
    void infoSourceReadsBesideTheApplicationsOwnJavaContributor() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(InfoContributorAutoConfiguration.class))
                .withPropertyValues("management.info.java.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(JavaInfoContributor.class);
                    info(context).containsKeys("java", "os", "process");
                });
    }

    /** A contributor bean would reach the application's own InfoEndpoint. */
    @Test
    void registersNoInfoContributorBean() {
        contextRunner.run(context -> assertThat(context).doesNotHaveBean(InfoContributor.class));
    }

    /** The sources with no visibility gate of their own: present, and reading something. */
    @ParameterizedTest
    @ValueSource(strings = {"spring", "info", "scheduledtasks"})
    void readsEverySourceThatNeedsNoBackingBean(String id) {
        contextRunner.run(context -> assertThat(read(context, id)).isNotNull());
    }

    @Test
    void contributesNoSourcesWhenPeekabootIsDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(InsightsSource.class));
    }

    /** A bound {@code @ConfigurationProperties} bean, so a sanitized value has something to hide. */
    @ConfigurationProperties("fixture")
    record FixtureProperties(String value) {}

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(FixtureProperties.class)
    static class FixturePropertiesConfig {}

    @Configuration(proxyBeanMethods = false)
    static class BuildContributorConfig {

        @Bean
        InfoContributor buildContributor() {
            return builder -> builder.withDetail("build", Map.of("version", "from-bean"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class LiquibaseConfig {

        @Bean
        EmbeddedDatabase liquibaseDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean
        SpringLiquibase liquibase(DataSource dataSource) {
            return liquibaseOn(dataSource);
        }

        static SpringLiquibase liquibaseOn(DataSource dataSource) {
            SpringLiquibase liquibase = new SpringLiquibase();
            liquibase.setDataSource(dataSource);
            liquibase.setChangeLog("classpath:db/changelog/insights-source-test.yaml");
            // Liquibase's defaults print to stdout and phone home; this keeps the test output clean.
            liquibase.setUiService(UIServiceEnum.LOGGER);
            liquibase.setShowSummaryOutput(UpdateSummaryOutputEnum.LOG);
            liquibase.setAnalyticsEnabled(false);
            return liquibase;
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateLiquibaseConfig {

        @Bean
        EmbeddedDatabase secondaryDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean(defaultCandidate = false)
        SpringLiquibase secondaryLiquibase(DataSource dataSource) {
            return LiquibaseConfig.liquibaseOn(dataSource);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class NonDefaultCandidateFlywayConfig {

        @Bean
        EmbeddedDatabase secondaryDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .generateUniqueName(true)
                    .build();
        }

        @Bean(defaultCandidate = false)
        Flyway secondaryFlyway(DataSource dataSource) {
            return Flyway.configure().dataSource(dataSource).load();
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class HealthEndpointConfig {
        @Bean
        HealthEndpoint healthEndpoint() {
            DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
            registry.registerContributor("db", (HealthIndicator)
                    () -> Health.up().withDetail("database", "H2").build());
            return new HealthEndpoint(registry, null, HealthEndpointGroups.of(new HidingGroup(), Map.of()), null);
        }
    }

    /** The health beans Boot's auto-configuration provides, each indicator bean registered under its name. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(HealthEndpointProperties.class)
    static class HealthRegistryConfig {

        @Bean
        DefaultHealthContributorRegistry healthContributorRegistry(ApplicationContext context) {
            DefaultHealthContributorRegistry registry = new DefaultHealthContributorRegistry();
            context.getBeansOfType(HealthIndicator.class).forEach(registry::registerContributor);
            return registry;
        }

        @Bean
        HealthEndpointGroups healthEndpointGroups() {
            return HealthEndpointGroups.of(new HidingGroup(), Map.of());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @Import(HealthRegistryConfig.class)
    static class HealthBeansConfig {

        @Bean
        HealthEndpoint healthEndpoint(HealthContributorRegistry registry, HealthEndpointGroups groups) {
            return new HealthEndpoint(registry, null, groups, null);
        }
    }

    private static final class HidingGroup implements HealthEndpointGroup {

        @Override
        public boolean isMember(String name) {
            return true;
        }

        @Override
        public boolean showComponents(SecurityContext securityContext) {
            return false;
        }

        @Override
        public boolean showDetails(SecurityContext securityContext) {
            return false;
        }

        @Override
        public StatusAggregator getStatusAggregator() {
            return StatusAggregator.getDefault();
        }

        @Override
        public HttpCodeStatusMapper getHttpCodeStatusMapper() {
            return HttpCodeStatusMapper.getDefault();
        }

        @Override
        public AdditionalHealthEndpointPath getAdditionalPath() {
            return null;
        }
    }

    /** Bounded, so a source without a deadline fails the test instead of hanging it. */
    private static Health upOnceReleased(CountDownLatch release) {
        try {
            release.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Health.up().build();
    }

    private static List<String> messages(LogCapture log) {
        return log.events().stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static MapAssert<String, Object> info(ApplicationContext context) {
        return assertThat(read(context, "info"))
                .asInstanceOf(InstanceOfAssertFactories.map(String.class, Object.class));
    }

    private static Object read(ApplicationContext context, String id) {
        return context.getBeansOfType(InsightsSource.class).values().stream()
                .filter(source -> source.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source with id " + id))
                .read()
                .get();
    }
}
