package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.sql.DataSource;
import liquibase.UpdateSummaryOutputEnum;
import liquibase.integration.spring.SpringLiquibase;
import liquibase.ui.UIServiceEnum;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.peekaboot.backend.actuator.InsightsSource;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesBeanDescriptor;
import org.springframework.boot.actuate.context.properties.ConfigurationPropertiesReportEndpoint.ConfigurationPropertiesDescriptor;
import org.springframework.boot.actuate.endpoint.SecurityContext;
import org.springframework.boot.actuate.env.EnvironmentEndpoint.EnvironmentDescriptor;
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
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.liquibase.actuate.endpoint.LiquibaseEndpoint.ChangeSetDescriptor;
import org.springframework.boot.liquibase.actuate.endpoint.LiquibaseEndpoint.LiquibaseBeansDescriptor;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class ActuatorSourcesAutoConfigurationTest {

    // java.util.logging is out of logback's reach; the strong reference keeps the level from being collected.
    private static final Logger LIQUIBASE_LOGGER = Logger.getLogger("liquibase");

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

    private static Object read(ApplicationContext context, String id) {
        return context.getBeansOfType(InsightsSource.class).values().stream()
                .filter(source -> source.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no source with id " + id))
                .read()
                .get();
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

    /**
     * Answers like Spring's default group does for an anonymous caller of
     * {@code /actuator/health}: neither components nor details. A descriptor that still
     * carries them can only have come from the endpoint bean's own {@code health()}.
     */
    @Test
    void healthSourceReadsTheEndpointBeanWithComponentsAndDetails() {
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
}
