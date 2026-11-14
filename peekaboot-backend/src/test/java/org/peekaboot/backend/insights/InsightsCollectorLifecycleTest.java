package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.peekaboot.backend.insights.InsightsCollectorBuilder.collector;
import static org.peekaboot.backend.testsupport.SeriesDefs.value;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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

    /**
     * A thread that dies waits for the collector's monitor before it is replaced. A stop()
     * and a start() can get in first. The level then has a thread of the new run, and the
     * dead thread must leave it alone, or two threads tick and stop() ends only one.
     */
    @Test
    void aLevelThreadThatDiedBeforeARestartLeavesTheNewRunItsOwnThread() throws Exception {
        AtomicReference<Thread> dying = new AtomicReference<>();
        List<Thread> ticked = new CopyOnWriteArrayList<>();
        InsightsCollector.Listener listener = new InsightsCollector.Listener() {
            @Override
            public void onTick(long epochMs, Map<String, Double> values) {
                if (dying.compareAndSet(null, Thread.currentThread())) {
                    throw new Error("listener broke");
                }
                ticked.add(Thread.currentThread());
            }

            @Override
            public void onRollUp(int level, long epochMs, Map<String, AggregateStats> entries) {}
        };
        InsightsCollector collector = collector(new SimpleMeterRegistry())
                .levels(InsightsProperties.Level.of(Duration.ofMillis(100), 20))
                .listener(listener)
                .build();
        try (LogCapture capture = LogCapture.attach(InsightsCollector.class)) {
            try {
                synchronized (collector) {
                    collector.start();
                    await().atMost(Duration.ofSeconds(5))
                            .alias("the dying thread waits for the monitor to replace itself")
                            .until(() -> dying.get() != null && dying.get().getState() == Thread.State.BLOCKED);
                    // stop() cannot join a thread that waits for this monitor; interrupted, it does not try
                    Thread.currentThread().interrupt();
                    try {
                        collector.stop();
                    } finally {
                        Thread.interrupted();
                    }
                    collector.start();
                }
                dying.get().join(Duration.ofSeconds(5));
                assertThat(dying.get().isAlive()).as("the dead thread is done").isFalse();

                int ticksOfTheNewRun = ticked.size() + 4;
                await().atMost(Duration.ofSeconds(5))
                        .alias("the new run ticks")
                        .until(() -> ticked.size() >= ticksOfTheNewRun);
                assertThat(Set.copyOf(ticked))
                        .as("the threads that tick in the new run")
                        .hasSize(1);
            } finally {
                collector.stop();
            }

            assertThat(capture.appender().list)
                    .extracting(
                            event -> event.getLevel(),
                            event -> event.getThrowableProxy().getMessage())
                    .containsExactly(tuple(Level.ERROR, "listener broke"));
        }
    }
}
