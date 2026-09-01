package org.peekaboot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.peekaboot.backend.insights.InsightsService;
import org.peekaboot.backend.insights.web.InsightsController;
import org.peekaboot.backend.insights.web.InsightsSsePublisher;
import org.peekaboot.backend.service.PeekabootActuatorService;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class InsightsAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PeekabootAutoConfiguration.class, InsightsAutoConfiguration.class, JacksonAutoConfiguration.class))
            .withUserConfiguration(MockActuatorConfig.class)
            .withPropertyValues("peekaboot.enabled=true");

    @Test
    void activatesWithMeterRegistryAndPeekabootEnabled() {
        contextRunner.withUserConfiguration(MeterRegistryConfig.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(InsightsService.class);
            assertThat(context).hasSingleBean(InsightsSsePublisher.class);
            assertThat(context).hasSingleBean(InsightsController.class);
        });
    }

    @Test
    void backsOffWithoutMeterRegistry() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(InsightsService.class);
        });
    }

    @Test
    void backsOffWhenInsightsDisabled() {
        contextRunner
                .withUserConfiguration(MeterRegistryConfig.class)
                .withPropertyValues("peekaboot.insights.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InsightsService.class);
                });
    }

    /**
     * The proof that the storage bean really reaches the collector is a snapshot on disk:
     * the service stops, and its final write lands in the configured directory. Nothing
     * short of that distinguishes a wired store from a service that merely started.
     */
    @Test
    void startsWithPersistenceWiredWhenStorageIsEnabled(@TempDir Path tempDir) {
        storageRunner(tempDir, true).run(context -> {
            assertThat(context).hasNotFailed();
            InsightsService service = context.getBean(InsightsService.class);
            assertThat(service.isRunning()).isTrue();

            // a snapshot is only written once the rings hold something, so wait for a real tick
            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> service.data(0).count() > 0);
            service.stop();

            assertThat(tempDir.resolve("insights.snapshot")).exists();
        });
    }

    @Test
    void writesNothingWhenStorageIsDisabled(@TempDir Path tempDir) {
        storageRunner(tempDir, false).run(context -> {
            assertThat(context).hasNotFailed();
            InsightsService service = context.getBean(InsightsService.class);

            await().atMost(Duration.ofSeconds(5))
                    .pollInterval(Duration.ofMillis(20))
                    .until(() -> service.data(0).count() > 0);
            service.stop();

            try (var files = Files.list(tempDir)) {
                assertThat(files.toList()).isEmpty();
            }
        });
    }

    @Test
    void backsOffWhenPeekabootDisabled() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class,
                        InsightsAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class, MeterRegistryConfig.class)
                .withPropertyValues("peekaboot.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(InsightsService.class);
                });
    }

    /** A single 100ms level, so one tick arrives in well under the awaits above. */
    private WebApplicationContextRunner storageRunner(Path tempDir, boolean storageEnabled) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        PeekabootAutoConfiguration.class,
                        PeekabootStorageAutoConfiguration.class,
                        InsightsAutoConfiguration.class,
                        JacksonAutoConfiguration.class))
                .withUserConfiguration(MockActuatorConfig.class, MeterRegistryConfig.class)
                .withPropertyValues(
                        "peekaboot.enabled=true",
                        "peekaboot.storage.enabled=" + storageEnabled,
                        "peekaboot.storage.dir=" + tempDir,
                        "peekaboot.insights.levels[0].interval=100ms",
                        "peekaboot.insights.levels[0].size=50");
    }

    @Configuration
    static class MeterRegistryConfig {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Configuration
    static class MockActuatorConfig {
        @Bean
        PeekabootActuatorService peekabootActuatorService() {
            return mock(PeekabootActuatorService.class);
        }
    }
}
