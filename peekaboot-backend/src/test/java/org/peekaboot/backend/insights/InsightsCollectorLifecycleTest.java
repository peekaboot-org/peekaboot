package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.peekaboot.backend.testsupport.SeriesDefs.value;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.backend.testsupport.InsightsCollectors;

class InsightsCollectorLifecycleTest {

    @Test
    void startTicksAndRollsUpOnSchedule() throws Exception {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Gauge.builder("g", () -> 1).register(registry);
        CountDownLatch ticks = new CountDownLatch(3);
        CountDownLatch rollups = new CountDownLatch(1);
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {
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
                List.of(value("g", "g")),
                List.of(),
                registry,
                listener,
                InsightsCollectors.noSnapshot());
        collector.start();
        try {
            assertThat(ticks.await(3, TimeUnit.SECONDS)).as("ticks arrived").isTrue();
            assertThat(rollups.await(3, TimeUnit.SECONDS)).as("rollup arrived").isTrue();
        } finally {
            collector.stop();
        }
        assertThat(collector.isRunning()).isFalse();
    }
}
