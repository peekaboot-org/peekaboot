package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.peekaboot.backend.insights.InsightsCollectorBuilder.collector;
import static org.peekaboot.backend.testsupport.SeriesDefs.value;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.InsightsProperties;
import org.peekaboot.testsupport.LogCapture;

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
        InsightsCollector collector = collector(registry)
                .levels(
                        InsightsProperties.Level.of(Duration.ofMillis(100), 20),
                        InsightsProperties.Level.of(Duration.ofMillis(500), 10))
                .series(value("g", "g"))
                .listener(listener)
                .build();
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
    void aLevelThreadThatDiesOfAnErrorIsStartedAgain() throws Exception {
        AtomicBoolean firstTick = new AtomicBoolean(true);
        CountDownLatch laterTicks = new CountDownLatch(2);
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {
                if (firstTick.getAndSet(false)) {
                    throw new Error("listener broke");
                }
                laterTicks.countDown();
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };
        InsightsCollector collector = collector(new SimpleMeterRegistry())
                .levels(InsightsProperties.Level.of(Duration.ofMillis(100), 20))
                .listener(listener)
                .build();
        try (LogCapture capture = LogCapture.attach(InsightsCollector.class)) {
            collector.start();
            try {
                assertThat(laterTicks.await(5, TimeUnit.SECONDS))
                        .as("ticks after the error")
                        .isTrue();

                assertThat(capture.appender().list)
                        .extracting(
                                event -> event.getLevel(),
                                event -> event.getFormattedMessage(),
                                event -> event.getThrowableProxy().getMessage())
                        .containsExactly(tuple(
                                Level.ERROR,
                                "Peekaboot insights: the peekaboot-insights-tick thread ended on an error;"
                                        + " starting it again",
                                "listener broke"));
                assertThat(collector.isRunning()).isTrue();
            } finally {
                collector.stop();
            }
        }
    }

    /**
     * {@code stop()} joins the level threads while it holds the collector's monitor, so a
     * thread that dies then must not wait for it.
     */
    @Test
    void aLevelThreadThatDiesWhileTheCollectorStopsDoesNotHoldItUp() throws Exception {
        AtomicReference<Thread> tickThread = new AtomicReference<>();
        CountDownLatch ticking = new CountDownLatch(1);
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {
                tickThread.set(Thread.currentThread());
                ticking.countDown();
                try {
                    new CountDownLatch(1).await();
                } catch (InterruptedException e) {
                    throw new Error("listener broke", e);
                }
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };
        InsightsCollector collector = collector(new SimpleMeterRegistry())
                .levels(InsightsProperties.Level.of(Duration.ofMillis(100), 20))
                .listener(listener)
                .build();
        try (LogCapture capture = LogCapture.attach(InsightsCollector.class)) {
            collector.start();
            try {
                assertThat(ticking.await(5, TimeUnit.SECONDS))
                        .as("a tick arrived")
                        .isTrue();
            } finally {
                collector.stop();
            }

            assertThat(tickThread.get().isAlive())
                    .as("the tick thread outlived stop()")
                    .isFalse();
            assertThat(capture.appender().list)
                    .extracting(
                            event -> event.getLevel(),
                            event -> event.getThrowableProxy().getMessage())
                    .containsExactly(tuple(Level.ERROR, "listener broke"));
            assertThat(collector.isRunning()).isFalse();
        }
    }
}
