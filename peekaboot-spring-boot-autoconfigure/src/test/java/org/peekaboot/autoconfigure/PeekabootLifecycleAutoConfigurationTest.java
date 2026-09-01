package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.ApplicationReadyListener;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.backend.lifecycle.BuildInfoProvider;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.lifecycle.HikariPoolInfo;
import org.peekaboot.backend.lifecycle.LifecycleEventLog;
import org.peekaboot.backend.lifecycle.LifecycleEventRecorder;
import org.peekaboot.backend.lifecycle.LifecycleRuns;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Verifies that the lifecycle auto-configuration is ordered after the Boot
 * auto-configurations providing the beans its conditions depend on
 * (BuildProperties, DataSource). Without explicit ordering, alphabetical
 * sorting evaluates org.peekaboot.* conditions before org.springframework.*
 * registers those beans, so they could never match.
 */
class PeekabootLifecycleAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootLifecycleAutoConfiguration.class,
                    PeekabootStorageAutoConfiguration.class,
                    ProjectInfoAutoConfiguration.class,
                    org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void buildInfoProviderUsesBuildPropertiesWhenAvailable() {
        contextRunner
                .withPropertyValues("spring.info.build.location=classpath:test-build-info.properties")
                .run(context -> {
                    assertThat(context).hasBean("buildInfoProvider");
                    assertThat(context).doesNotHaveBean("buildInfoProviderFallback");
                    assertThat(context.getBean(BuildInfoProvider.class).isBuildInfoAvailable())
                            .isTrue();
                    assertThat(context.getBean(BuildInfoProvider.class).getVersion())
                            .isEqualTo("1.2.3");
                });
    }

    @Test
    void fallbackBuildInfoProviderUsedWithoutBuildProperties() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("buildInfoProviderFallback");
            assertThat(context).hasSingleBean(BuildInfoProvider.class);
        });
    }

    @Test
    void databaseMetadataListCreatedForAutoConfiguredDataSource() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:lifecycletest;DB_CLOSE_DELAY=-1")
                .run(context -> {
                    assertThat(context).hasBean("databaseMetadataList");
                    assertThat(context).hasSingleBean(ApplicationReadyListener.class);
                });
    }

    @Test
    void theStoppedBannerIsRegisteredAlongsideTheReadyOne() {
        contextRunner.run(context -> assertThat(context).hasSingleBean(ApplicationStoppedListener.class));
    }

    @Test
    void theEventLogIsWiredAndRunsInMemoryUntilStorageIsEnabled() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(LifecycleEventLog.class);
            assertThat(context).hasSingleBean(LifecycleEventRecorder.class);
            assertThat(context).hasSingleBean(LifecycleRuns.class);
            assertThat(context.getBean(LifecycleEventLog.class).events()).isEmpty();
        });
    }

    @Test
    void bannersStillWorkWithNoStorageBeanAtAll() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootLifecycleAutoConfiguration.class,
                        ProjectInfoAutoConfiguration.class,
                        org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration.class))
                .withPropertyValues("peekaboot.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ApplicationReadyListener.class);
                    assertThat(context).hasSingleBean(ApplicationStoppedListener.class);
                    assertThat(context).doesNotHaveBean(LifecycleEventLog.class);
                    assertThat(context).doesNotHaveBean(LifecycleEventRecorder.class);
                    assertThat(context).doesNotHaveBean(LifecycleRuns.class);
                });
    }

    @Test
    void canBeDisabledViaProperty() {
        contextRunner
                .withPropertyValues("peekaboot.lifecycle.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationReadyListener.class));
    }

    @Test
    void disabledWhenPeekabootGloballyDisabled() {
        contextRunner
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationReadyListener.class));
    }

    @Test
    void disabledWhenGlobalEnabledPropertyMissing() {
        // matchIfMissing = false: without the environment post-processor's detected
        // default the safe fallback is off
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(PeekabootLifecycleAutoConfiguration.class))
                .run(context -> assertThat(context).doesNotHaveBean(ApplicationReadyListener.class));
    }

    @Test
    void theReadyBannerReportsTheHikariPoolWhenHikariIsOnTheClasspath() {
        contextRunner
                .withPropertyValues("spring.datasource.url=jdbc:h2:mem:lifecyclepool;DB_CLOSE_DELAY=-1")
                .run(context -> {
                    assertThat(context).hasSingleBean(HikariPoolInfo.class);
                    assertThat(readyBanner(context)).contains(" DB Pool: minimumIdle=");
                });
    }

    /**
     * An application on another pool (tomcat-jdbc, DBCP2, a JNDI DataSource) has no HikariCP
     * on its classpath. The banner must still be logged with the DataSource's details, minus
     * the pool lines - a NoClassDefFoundError inside the ApplicationReadyEvent listener would
     * fail the whole start.
     */
    @Test
    void theReadyBannerSurvivesADataSourceOnAnotherPoolWithoutHikariOnTheClasspath() {
        contextRunner
                .withClassLoader(new FilteredClassLoader(HikariDataSource.class))
                .withUserConfiguration(PlainH2DataSourceConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(HikariPoolInfo.class);
                    assertThat(readyBanner(context))
                            .contains(" DB Connection [plainDataSource]")
                            .contains(" DB Version: H2")
                            .doesNotContain("DB Pool");
                });
    }

    @Test
    void brokenDataSourceDoesNotFailStartup() {
        ListAppender<ILoggingEvent> appender = attachListAppender(DataSourceMetadata.class);
        try {
            contextRunner.withUserConfiguration(BrokenDataSourceConfig.class).run(context -> {
                assertThat(context).hasNotFailed();
                assertThat(context).hasBean("databaseMetadataList");
                assertThat(context.getBean("databaseMetadataList", List.class)).isEmpty();
            });

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Failed to extract metadata from DataSource 'brokenDataSource': db down");
            });
        } finally {
            detachListAppender(DataSourceMetadata.class, appender);
        }
    }

    /** Fires the ApplicationReadyEvent at the context's listener and returns the banner it logs. */
    private static String readyBanner(AssertableApplicationContext context) {
        ListAppender<ILoggingEvent> appender = attachListAppender(ApplicationReadyListener.class);
        try {
            ApplicationReadyEvent event = new ApplicationReadyEvent(
                    new SpringApplication(), new String[0], context.getSourceApplicationContext(), Duration.ZERO);
            context.getBean(ApplicationReadyListener.class).onApplicationEvent(event);
            assertThat(appender.list).hasSize(1);
            return appender.list.get(0).getFormattedMessage();
        } finally {
            detachListAppender(ApplicationReadyListener.class, appender);
        }
    }

    /**
     * Captures a class's log events instead of letting them reach the console, so a test can
     * assert on them ({@link #brokenDataSourceDoesNotFailStartup()} on a WARN, the banner tests
     * on the INFO banner itself).
     */
    private static ListAppender<ILoggingEvent> attachListAppender(Class<?> loggerClass) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return appender;
    }

    private static void detachListAppender(Class<?> loggerClass, ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(loggerClass);
        logger.detachAppender(appender);
        logger.setAdditive(true);
    }

    @Configuration
    static class PlainH2DataSourceConfig {

        @Bean
        DataSource plainDataSource() {
            JdbcDataSource dataSource = new JdbcDataSource();
            dataSource.setURL("jdbc:h2:mem:plainpool;DB_CLOSE_DELAY=-1");
            return dataSource;
        }
    }

    @Configuration
    static class BrokenDataSourceConfig {

        @Bean
        DataSource brokenDataSource() throws SQLException {
            DataSource dataSource = mock(DataSource.class);
            when(dataSource.getConnection()).thenThrow(new SQLException("db down"));
            return dataSource;
        }
    }
}
