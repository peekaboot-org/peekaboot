package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.lifecycle.ApplicationReadyListener;
import org.peekaboot.backend.lifecycle.ApplicationStoppedListener;
import org.peekaboot.backend.lifecycle.BuildInfoProvider;
import org.peekaboot.backend.lifecycle.DataSourceMetadata;
import org.peekaboot.backend.lifecycle.LifecycleEventLog;
import org.peekaboot.backend.lifecycle.LifecycleEventRecorder;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.info.ProjectInfoAutoConfiguration;
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
    void brokenDataSourceDoesNotFailStartup() {
        ListAppender<ILoggingEvent> appender = attachListAppender();
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
            detachListAppender(appender);
        }
    }

    /**
     * Captures {@link DataSourceMetadata}'s WARN log instead of letting it reach the
     * console; {@link #brokenDataSourceDoesNotFailStartup()} asserts on the captured event.
     */
    private static ListAppender<ILoggingEvent> attachListAppender() {
        Logger logger = (Logger) LoggerFactory.getLogger(DataSourceMetadata.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        return appender;
    }

    private static void detachListAppender(ListAppender<ILoggingEvent> appender) {
        Logger logger = (Logger) LoggerFactory.getLogger(DataSourceMetadata.class);
        logger.detachAppender(appender);
        logger.setAdditive(true);
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
