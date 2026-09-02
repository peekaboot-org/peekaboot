package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.insights.InsightsConfigResponse;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.testsupport.LogCapture;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

class InsightsServiceTest {

    private SimpleMeterRegistry registry;
    private InsightsService service;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Gauge.builder("process.cpu.usage", () -> 1.0).register(registry); // resolves the cpu panel's first series
        service = new InsightsService(
                registry,
                new InsightsProperties(),
                new DefaultResourceLoader(),
                InsightsCollector.Listener.NO_OP,
                null);
    }

    @Test
    void configListsEnabledPanelsInOrderWithNamespacedSeriesIds() {
        InsightsConfigResponse config = service.config();
        assertThat(config.levels()).hasSize(3);
        assertThat(config.levels().get(0).intervalMs()).isEqualTo(10_000);
        assertThat(config.panels().get(0).id()).isEqualTo("cpu");
        assertThat(config.panels().get(0).series().get(0).id()).isEqualTo("cpu.process");
        assertThat(config.panels())
                .extracting(InsightsConfigResponse.Panel::id)
                .doesNotContain("thread-states"); // disabled by default
        assertThat(config.tiles()).hasSize(4);
    }

    @Test
    void dataSnapshotsMapNaNToNull() {
        service.collector().tick(10_000); // most default meters unresolved in SimpleMeterRegistry
        LevelDataResponse data = service.data(0);
        assertThat(data.count()).isEqualTo(1);
        assertThat(data.series().get("cpu.process").values()).containsExactly(1.0);
        assertThat(data.series().get("heap.used").values()).containsExactly((Double) null);
    }

    @Test
    void rejectsUnknownLevel() {
        assertThatThrownBy(() -> service.data(7)).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * The panel file is the user's, not ours: a typo in it may cost them the Insights
     * tab's customisation, but never the application it is embedded in.
     */
    @Test
    void invalidUserPanelFileIsIgnoredInFavourOfTheDefaults() {
        InsightsProperties properties = new InsightsProperties();
        properties.setConfigLocation("classpath:insights/loader-invalid.yml");

        try (LogCapture logs = LogCapture.attach(InsightsService.class)) {
            InsightsService fallback = new InsightsService(
                    registry, properties, new DefaultResourceLoader(), InsightsCollector.Listener.NO_OP, null);

            assertThat(fallback.config().panels())
                    .extracting(InsightsConfigResponse.Panel::id)
                    .as("the bundled panels, none of them from the broken file")
                    .contains("cpu", "heap")
                    .doesNotContain("broken");
            assertThat(logs.appender().list).anySatisfy(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.ERROR);
                assertThat(event.getFormattedMessage()).contains("loader-invalid.yml");
                assertThat(event.getThrowableProxy().getMessage()).contains("bogus");
            });
        }
    }

    /**
     * The bundled panels reach the service through the injected {@link ResourceLoader},
     * not through a classloader of its own choosing: a host application that supplies its
     * own loader has to be able to see, and to answer, the request.
     */
    @Test
    void theBundledPanelConfigIsAskedForThroughTheResourceLoader() {
        List<String> requested = new ArrayList<>();
        ResourceLoader recording = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                requested.add(location);
                return super.getResource(location);
            }
        };

        InsightsService loaded = new InsightsService(
                registry, new InsightsProperties(), recording, InsightsCollector.Listener.NO_OP, null);

        assertThat(requested).contains("classpath:peekaboot-insights-defaults.yml");
        assertThat(loaded.config().panels())
                .extracting(InsightsConfigResponse.Panel::id)
                .contains("cpu", "heap");
    }

    /** A broken file of ours is a bug of ours, and must not be papered over. */
    @Test
    void invalidBundledPanelFileFailsFast() {
        ResourceLoader brokenDefaults = new DefaultResourceLoader() {
            @Override
            public Resource getResource(String location) {
                return location.contains("peekaboot-insights-defaults")
                        ? new ClassPathResource("insights/loader-invalid.yml")
                        : super.getResource(location);
            }
        };

        assertThatThrownBy(() -> new InsightsService(
                        registry, new InsightsProperties(), brokenDefaults, InsightsCollector.Listener.NO_OP, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bogus");
    }

    @Test
    void logsMemoryEstimateOnStart() {
        try (LogCapture logs = LogCapture.attach(InsightsService.class)) {
            service.start();
            service.stop();
            assertThat(logs.appender().list).anySatisfy(event -> {
                assertThat(event.getFormattedMessage()).contains("Peekaboot insights:");
                assertThat(event.getFormattedMessage()).contains("series");
                assertThat(event.getFormattedMessage()).containsPattern("~\\d+(\\.\\d+)? MB");
            });
        }
    }
}
