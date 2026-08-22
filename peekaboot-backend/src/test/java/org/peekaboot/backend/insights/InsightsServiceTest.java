package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.domain.insights.InsightsConfigResponse;
import org.peekaboot.backend.domain.insights.LevelDataResponse;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.testsupport.LogCapture;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class InsightsServiceTest {

    private SimpleMeterRegistry registry;
    private InsightsService service;
    // Micrometer gauges hold the state object via a WeakReference; without a strong
    // reference here the GC can collect it between registration and sampling.
    private AtomicLong cpuUsage;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        cpuUsage = registry.gauge("process.cpu.usage", new AtomicLong(1)); // resolves the cpu panel's first series
        service = new InsightsService(registry, new InsightsProperties(),
                new DefaultResourceLoader(), InsightsCollector.Listener.NO_OP);
    }

    @Test
    void configListsEnabledPanelsInOrderWithNamespacedSeriesIds() {
        InsightsConfigResponse config = service.config();
        assertThat(config.levels()).hasSize(3);
        assertThat(config.levels().get(0).intervalMs()).isEqualTo(10_000);
        assertThat(config.panels().get(0).id()).isEqualTo("cpu");
        assertThat(config.panels().get(0).series().get(0).id()).isEqualTo("cpu.process");
        assertThat(config.panels()).extracting(InsightsConfigResponse.Panel::id)
                .doesNotContain("thread-states"); // disabled by default
        assertThat(config.tiles()).hasSize(9);
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
