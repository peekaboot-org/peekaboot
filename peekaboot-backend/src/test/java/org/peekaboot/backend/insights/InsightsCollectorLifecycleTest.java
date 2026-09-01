package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.insights.config.SeriesDef;

class InsightsCollectorLifecycleTest {

    @Test
    void startTicksAndRollsUpOnSchedule() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("g", () -> 1).register(registry);
        CountDownLatch ticks = new CountDownLatch(3);
        CountDownLatch rollups = new CountDownLatch(1);
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values, Map<String, Double> tiles) {
                ticks.countDown();
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {
                rollups.countDown();
            }
        };
        InsightsCollector collector = new InsightsCollector(
                List.of(
                        InsightsProperties.Level.of(Duration.ofMillis(100), 20),
                        InsightsProperties.Level.of(Duration.ofMillis(500), 10)),
                List.of(new SeriesDef("g", "G", "g", Map.of(), "value", null, null)),
                List.of(),
                registry,
                listener);
        collector.start();
        try {
            assertThat(ticks.await(3, TimeUnit.SECONDS)).as("ticks arrived").isTrue();
            assertThat(rollups.await(3, TimeUnit.SECONDS)).as("rollup arrived").isTrue();
        } finally {
            collector.stop();
        }
        assertThat(collector.isRunning()).isFalse();
    }

    @Test
    void threadNamesFollowConvention() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        InsightsCollector collector = new InsightsCollector(
                List.of(
                        InsightsProperties.Level.of(Duration.ofSeconds(10), 9),
                        InsightsProperties.Level.of(Duration.ofMinutes(1), 9),
                        InsightsProperties.Level.of(Duration.ofHours(1), 9)),
                List.of(),
                List.of(),
                registry,
                InsightsCollector.Listener.NO_OP);
        assertThat(collector.threadNames())
                .containsExactly("peekaboot-insights-tick", "peekaboot-insights-agg-1m", "peekaboot-insights-agg-1h");
    }
}
