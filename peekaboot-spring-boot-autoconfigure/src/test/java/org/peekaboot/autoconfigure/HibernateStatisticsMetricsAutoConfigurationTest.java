package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.orm.micrometer.HibernateMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

class HibernateStatisticsMetricsAutoConfigurationTest {

    /** hibernate-micrometer is absent, as in most JPA applications; the stub stands in for it otherwise. */
    private final WebApplicationContextRunner withoutHibernateMicrometer = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(HibernateStatisticsMetricsAutoConfiguration.class))
            .withClassLoader(new FilteredClassLoader(HibernateMetrics.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withPropertyValues("peekaboot.enabled=true");

    /** The same ids hibernate-micrometer's HibernateMetrics registers for these four names, as function counters. */
    @Test
    void bindsTheFourPanelMetersFromTheStatistics() {
        withoutHibernateMicrometer.withUserConfiguration(StatisticsOn.class).run(context -> {
            assertThat(context).hasSingleBean(HibernateStatisticsMeterBinder.class);
            assertThat(hibernateMeters(context))
                    .containsExactlyInAnyOrder(
                            "hibernate.entities.loads{entityManagerFactory=entityManagerFactory}",
                            "hibernate.sessions.open{entityManagerFactory=entityManagerFactory}",
                            "hibernate.statements{entityManagerFactory=entityManagerFactory,status=closed}",
                            "hibernate.statements{entityManagerFactory=entityManagerFactory,status=prepared}",
                            "hibernate.transactions{entityManagerFactory=entityManagerFactory,result=failure}",
                            "hibernate.transactions{entityManagerFactory=entityManagerFactory,result=success}");
            assertThat(context.getBean(MeterRegistry.class).getMeters().stream()
                            .filter(meter -> meter.getId().getName().startsWith("hibernate.")))
                    .allMatch(FunctionCounter.class::isInstance);
        });
    }

    @Test
    void countsWhatTheStatisticsCount() {
        withoutHibernateMicrometer.withUserConfiguration(StatisticsOn.class).run(context -> {
            context.getBean(SessionFactory.class).inTransaction(session -> {});

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            assertThat(registry.get("hibernate.transactions")
                            .tag("result", "success")
                            .functionCounter()
                            .count())
                    .isEqualTo(1.0);
            assertThat(registry.get("hibernate.transactions")
                            .tag("result", "failure")
                            .functionCounter()
                            .count())
                    .isZero();
            assertThat(registry.get("hibernate.sessions.open").functionCounter().count())
                    .isEqualTo(1.0);
        });
    }

    /** Boot names the tag after the bean, less its entityManagerFactory suffix. */
    @Test
    void tagsEachFactoryAsBootsBinderDoes() {
        withoutHibernateMicrometer
                .withUserConfiguration(SecondaryStatisticsOn.class)
                .run(context -> assertThat(hibernateMeters(context))
                        .contains("hibernate.sessions.open{entityManagerFactory=secondary}"));
    }

    /** HibernateMetrics registers nothing while statistics are off, and neither does this binder. */
    @Test
    void bindsNothingWhileStatisticsAreOff() {
        withoutHibernateMicrometer.withUserConfiguration(StatisticsOff.class).run(context -> {
            assertThat(context).hasSingleBean(HibernateStatisticsMeterBinder.class);
            assertThat(hibernateMeters(context)).isEmpty();
        });
    }

    /** With hibernate-micrometer on the class path Boot's binder registers these meters itself. */
    @Test
    void stepsAsideWhereHibernateMicrometerIsPresent() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(HibernateStatisticsMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withUserConfiguration(StatisticsOn.class)
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(HibernateStatisticsMeterBinder.class);
                    assertThat(hibernateMeters(context)).isEmpty();
                });
    }

    @Test
    void bindsNothingWhileInsightsAreOff() {
        withoutHibernateMicrometer
                .withUserConfiguration(StatisticsOn.class)
                .withPropertyValues("peekaboot.insights.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(HibernateStatisticsMeterBinder.class));
    }

    private static List<String> hibernateMeters(AssertableWebApplicationContext context) {
        return context.getBean(MeterRegistry.class).getMeters().stream()
                .map(Meter::getId)
                .filter(id -> id.getName().startsWith("hibernate."))
                .map(id -> id.getName()
                        + id.getTags().stream()
                                .map(tag -> tag.getKey() + "=" + tag.getValue())
                                .collect(Collectors.joining(",", "{", "}")))
                .toList();
    }

    private static SessionFactory sessionFactory(String name, boolean statistics) {
        DataSource dataSource = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .setName("hibernate-meters-" + name)
                .build();
        Configuration configuration =
                new Configuration().setProperty(AvailableSettings.GENERATE_STATISTICS, statistics);
        configuration.getProperties().put(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, dataSource);
        return configuration.buildSessionFactory();
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class StatisticsOn {

        @Bean(destroyMethod = "close")
        SessionFactory entityManagerFactory() {
            return sessionFactory("on", true);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class SecondaryStatisticsOn {

        @Bean(destroyMethod = "close")
        SessionFactory secondaryEntityManagerFactory() {
            return sessionFactory("secondary", true);
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class StatisticsOff {

        @Bean(destroyMethod = "close")
        SessionFactory entityManagerFactory() {
            return sessionFactory("off", false);
        }
    }
}
