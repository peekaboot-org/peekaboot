package org.peekaboot.backend.insights;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.FunctionTimer;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.TimeGauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.peekaboot.backend.insights.SeriesSampler.UnresolvedMeterReport;
import org.peekaboot.backend.insights.config.MeterAlternative;
import org.peekaboot.backend.insights.config.SeriesDef;
import org.peekaboot.backend.insights.config.Stat;
import org.peekaboot.backend.insights.config.Unit;
import org.peekaboot.testsupport.LogCapture;

class SeriesSamplerTest {

    private static final String FAILED_READ_OF_BROKEN =
            "Peekaboot insights: series 'r': reading meter 'broken' failed; recording a gap until it reads again";

    private SimpleMeterRegistry registry;
    private final AtomicBoolean failing = new AtomicBoolean();
    // a field, since Micrometer holds a function meter's state weakly
    private final AtomicLong total = new AtomicLong();
    private final Calls calls = new Calls(new AtomicLong(), new AtomicLong());

    /** What a function timer reads: the calls so far and the time they took. */
    private record Calls(AtomicLong count, AtomicLong millis) {}

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    /** Registers a function counter over {@link #total} whose read throws while {@link #failing} is set. */
    private void brokenCounter(String name) {
        FunctionCounter.builder(name, total, t -> {
                    if (failing.get()) {
                        throw new IllegalStateException("meter broke");
                    }
                    return t.get();
                })
                .register(registry);
    }

    /** The rate series {@code r} over {@link #brokenCounter} {@code broken}. */
    private SeriesSampler rateOverBrokenCounter() {
        brokenCounter("broken");
        return new SeriesSampler(
                new SeriesDef("r", "R", "broken", Map.of(), Stat.RATE, null, null, List.of()),
                registry,
                UnresolvedMeterReport.WARN);
    }

    private static SeriesDef def(String meter, Map<String, String> tags, Stat stat, MeterAlternative... alternatives) {
        return new SeriesDef("s", "S", meter, tags, stat, null, null, List.of(alternatives));
    }

    @Test
    void unresolvedMeterYieldsNaN() {
        SeriesSampler sampler =
                new SeriesSampler(def("does.not.exist", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN();
    }

    @Test
    void resolvesMetersRegisteredAfterConstruction() {
        SeriesSampler sampler =
                new SeriesSampler(def("late.gauge", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN();
        Gauge.builder("late.gauge", () -> 42).register(registry);
        assertThat(sampler.sample(10_000)).isEqualTo(42.0);
    }

    @Test
    void sumsGaugesAcrossTagCombinations() {
        Gauge.builder("mem", () -> 100).tags("area", "heap", "id", "eden").register(registry);
        Gauge.builder("mem", () -> 200).tags("area", "heap", "id", "old").register(registry);
        Gauge.builder("mem", () -> 999).tags("area", "nonheap", "id", "meta").register(registry);
        SeriesSampler sampler =
                new SeriesSampler(def("mem", Map.of("area", "heap"), Stat.VALUE), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isEqualTo(300.0);
    }

    /** Micrometer's CPU and GC meters report a ratio; a series charted in percent serves it from 0 to 100. */
    @Test
    void aPercentSeriesServesItsRatioMeterAsAPercentage() {
        Gauge.builder("process.cpu.usage", () -> 0.42).register(registry);
        SeriesSampler sampler = new SeriesSampler(
                new SeriesDef("s", "S", "process.cpu.usage", Map.of(), Stat.VALUE, null, Unit.PERCENT, List.of()),
                registry,
                UnresolvedMeterReport.WARN);

        assertThat(sampler.sample(10_000)).isCloseTo(42.0, within(1e-9));
    }

    @Test
    void aSeriesInAnyOtherUnitServesItsMeterAsRead() {
        Gauge.builder("ratio", () -> 0.42).register(registry);
        SeriesSampler sampler = new SeriesSampler(
                new SeriesDef("s", "S", "ratio", Map.of(), Stat.VALUE, null, Unit.COUNT, List.of()),
                registry,
                UnresolvedMeterReport.WARN);

        assertThat(sampler.sample(10_000)).isEqualTo(0.42);
    }

    /** The started-at and startup tiles read time gauges; their values are epoch and duration milliseconds. */
    @Test
    void aTimeGaugeReadsInMilliseconds() {
        TimeGauge.builder("process.start.time", () -> 1_762_400_000_000L, TimeUnit.MILLISECONDS)
                .register(registry);
        SeriesSampler sampler = new SeriesSampler(
                def("process.start.time", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.WARN);

        assertThat(sampler.sample(10_000)).isEqualTo(1_762_400_000_000.0);
    }

    @Test
    void counterRateIsDeltaPerSecond() {
        Counter counter = registry.counter("hits");
        SeriesSampler sampler =
                new SeriesSampler(def("hits", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        counter.increment(5);
        assertThat(sampler.sample(10_000)).isNaN(); // first sample: no baseline
        counter.increment(20);
        assertThat(sampler.sample(10_000)).isCloseTo(2.0, within(1e-9)); // 20 in 10s
    }

    /** Micrometer registers the 5xx timer on the first 5xx, so a sample before it knows the count was 0. */
    @Test
    void aMeterRegisteredAfterAnEarlierSampleCountsFromZero() {
        SeriesSampler sampler = new SeriesSampler(
                def("http.server.requests", Map.of("outcome", "SERVER_ERROR"), Stat.RATE),
                registry,
                UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN(); // nothing to count yet
        Timer.builder("http.server.requests")
                .tag("outcome", "SERVER_ERROR")
                .register(registry)
                .record(Duration.ofMillis(5));
        assertThat(sampler.sample(10_000)).isCloseTo(0.1, within(1e-9)); // 1 in 10s
    }

    /** A re-registered meter counts from 0 again, so a sample that found it gone is a known baseline too. */
    @Test
    void aMeterBackAfterASampleFoundItGoneCountsFromZero() {
        Counter counter = registry.counter("hits");
        counter.increment(50);
        SeriesSampler sampler =
                new SeriesSampler(def("hits", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline 50
        registry.remove(counter);
        assertThat(sampler.sample(10_000)).isNaN(); // gone
        registry.counter("hits").increment(30);
        assertThat(sampler.sample(10_000)).isCloseTo(3.0, within(1e-9)); // 30 in 10s
    }

    /** The first request registers its timer, and its latency belongs in the tick it was answered in. */
    @Test
    void aTimerRegisteredAfterAnEarlierSampleAveragesItsFirstRecordings() {
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), Stat.AVG), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN(); // nothing to average yet
        Timer timer = registry.timer("req");
        timer.record(Duration.ofMillis(100));
        timer.record(Duration.ofMillis(300));
        assertThat(sampler.sample(10_000)).isCloseTo(200.0, within(1e-9));
    }

    @Test
    void timerAvgIsDeltaTotalOverDeltaCountInMillis() {
        Timer timer = registry.timer("req");
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), Stat.AVG), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline
        timer.record(Duration.ofMillis(100));
        timer.record(Duration.ofMillis(300));
        assertThat(sampler.sample(10_000)).isCloseTo(200.0, within(1e-9));
        // no new recordings in the next tick -> NaN, not 0
        assertThat(sampler.sample(10_000)).isNaN();
    }

    @Test
    void timerMaxInMillis() {
        Timer timer = registry.timer("req");
        timer.record(Duration.ofMillis(250));
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), Stat.MAX), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isCloseTo(250.0, within(1e-9));
    }

    @Test
    void longTaskTimerValueIsActiveTasks() {
        LongTaskTimer ltt = LongTaskTimer.builder("inflight").register(registry);
        LongTaskTimer.Sample running = ltt.start();
        SeriesSampler sampler =
                new SeriesSampler(def("inflight", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isEqualTo(1.0);
        running.stop();
        assertThat(sampler.sample(10_000)).isEqualTo(0.0);
    }

    @Test
    void subtractMeterComputesDifference() {
        Gauge.builder("disk.total", () -> 1000).register(registry);
        Gauge.builder("disk.free", () -> 400).register(registry);
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isEqualTo(600.0);
    }

    /** Micrometer registers meters lazily, so a few ticks are given to let one still appear. */
    @Test
    void unresolvedSubtractMeterIsNotLoggedBeforeTheWarningThreshold() {
        Gauge.builder("disk.total", () -> 1000).register(registry);
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free.typo", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            assertThat(sampler.sample(10_000)).isNaN();
            assertThat(sampler.sample(10_000)).isNaN();
            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** A misnamed subtract-meter can't be caught at config load, so it is reported once it has stayed unresolved. */
    @Test
    void unresolvedSubtractMeterIsLoggedOnceAfterTheWarningThresholdNotPerSample() {
        Gauge.builder("disk.total", () -> 1000).register(registry);
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free.typo", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            assertThat(sampler.sample(10_000)).isNaN();
            assertThat(sampler.sample(10_000)).isNaN();
            assertThat(sampler.sample(10_000)).isNaN(); // 3rd consecutive unresolved tick: threshold reached
            assertThat(sampler.sample(10_000)).isNaN(); // a further tick must not log again

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: series 'used' (meter 'disk.total'): "
                                + "subtract-meter 'disk.free.typo' did not resolve to any meter across 3 ticks");
            });
        }
    }

    /** A series whose own meter never resolved is already reported as such; warning about its subtract-meter too would be noise. */
    @Test
    void unresolvedSubtractMeterIsNotLoggedWhileThePrimaryMeterIsAlsoUnresolved() {
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            for (int tick = 0; tick < 5; tick++) {
                assertThat(sampler.sample(10_000)).isNaN();
            }
            assertThat(capture.appender().list)
                    .extracting(event -> event.getFormattedMessage())
                    .containsExactly("Peekaboot insights: series 'used': meter 'disk.total' did not resolve"
                            + " to any meter across 3 ticks");
        }
    }

    /** A misnamed meter can't be caught at config load, whatever the stat, so it is reported once it has stayed unresolved. */
    @ParameterizedTest
    @EnumSource(Stat.class)
    void unresolvedPrimaryMeterIsLoggedOnceAfterTheWarningThreshold(Stat stat) {
        SeriesSampler sampler = new SeriesSampler(def("my.typo", Map.of(), stat), registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            sampler.sample(10_000);
            sampler.sample(10_000);
            assertThat(capture.appender().list).isEmpty();
            sampler.sample(10_000); // 3rd consecutive unresolved tick: threshold reached
            sampler.sample(10_000); // a further tick must not log again

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: series 's': meter 'my.typo' did not resolve to any meter"
                                + " across 3 ticks");
            });
        }
    }

    /** Each outage is its own problem: a meter that resolved in between is reported again when it disappears again. */
    @Test
    void unresolvedPrimaryMeterIsLoggedAgainAfterItResolvedInBetween() {
        SeriesSampler sampler =
                new SeriesSampler(def("late.gauge", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            for (int tick = 0; tick < 3; tick++) {
                sampler.sample(10_000);
            }
            Gauge gauge = Gauge.builder("late.gauge", () -> 42).register(registry);
            assertThat(sampler.sample(10_000)).isEqualTo(42.0);
            registry.remove(gauge);
            for (int tick = 0; tick < 3; tick++) {
                assertThat(sampler.sample(10_000)).isNaN();
            }

            assertThat(capture.appender().list).hasSize(2);
        }
    }

    /** Shipped panels name meters an application may simply not have (no DataSource, no request yet): not worth a warning. */
    @Test
    void aShippedSeriesReportsBothOutagesAtDebug() {
        SeriesDef primaryMissing =
                new SeriesDef("a", "A", "hikaricp.connections", Map.of(), Stat.VALUE, null, null, List.of());
        Gauge.builder("disk.total", () -> 1000).register(registry);
        SeriesDef subtractMissing =
                new SeriesDef("b", "B", "disk.total", Map.of(), Stat.VALUE, "disk.free", null, List.of());
        SeriesSampler primary = new SeriesSampler(primaryMissing, registry, UnresolvedMeterReport.DEBUG);
        SeriesSampler subtract = new SeriesSampler(subtractMissing, registry, UnresolvedMeterReport.DEBUG);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class, Level.DEBUG)) {
            for (int tick = 0; tick < 3; tick++) {
                primary.sample(10_000);
                subtract.sample(10_000);
            }

            assertThat(capture.appender().list)
                    .extracting(event -> event.getLevel(), event -> event.getFormattedMessage())
                    .containsExactly(
                            tuple(
                                    Level.DEBUG,
                                    "Peekaboot insights: series 'a': meter 'hikaricp.connections' did not"
                                            + " resolve to any meter across 3 ticks"),
                            tuple(
                                    Level.DEBUG,
                                    "Peekaboot insights: series 'b' (meter 'disk.total'): subtract-meter"
                                            + " 'disk.free' did not resolve to any meter across 3 ticks"));
        }
    }

    @Test
    void aSamplerThatReportsNothingStaysSilent() {
        SeriesSampler sampler =
                new SeriesSampler(def("my.typo", Map.of(), Stat.VALUE), registry, UnresolvedMeterReport.NONE);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class, Level.TRACE)) {
            for (int tick = 0; tick < 5; tick++) {
                sampler.sample(10_000);
            }
            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** Once the meter appears, later samples resolve normally without needing a fresh SeriesSampler. */
    @Test
    void subtractMeterRegisteredAfterConstructionStopsBeingUnresolved() {
        Gauge.builder("disk.total", () -> 1000).register(registry);
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            assertThat(sampler.sample(10_000)).isNaN();
            assertThat(capture.appender().list).isEmpty(); // below the warning threshold

            Gauge.builder("disk.free", () -> 400).register(registry);
            assertThat(sampler.sample(10_000)).isEqualTo(600.0);
            assertThat(capture.appender().list).isEmpty();
        }
    }

    /** Each outage is its own problem: a subtract-meter that resolved in between is reported again when it disappears again. */
    @Test
    void unresolvedSubtractMeterIsLoggedAgainAfterItResolvedInBetween() {
        Gauge.builder("disk.total", () -> 1000).register(registry);
        SeriesDef diff =
                new SeriesDef("used", "Used", "disk.total", Map.of(), Stat.VALUE, "disk.free", null, List.of());
        SeriesSampler sampler = new SeriesSampler(diff, registry, UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            for (int tick = 0; tick < 3; tick++) {
                sampler.sample(10_000);
            }
            Gauge free = Gauge.builder("disk.free", () -> 400).register(registry);
            assertThat(sampler.sample(10_000)).isEqualTo(600.0);
            registry.remove(free);
            for (int tick = 0; tick < 3; tick++) {
                assertThat(sampler.sample(10_000)).isNaN();
            }

            assertThat(capture.appender().list)
                    .extracting(event -> event.getFormattedMessage())
                    .containsExactly(
                            "Peekaboot insights: series 'used' (meter 'disk.total'): "
                                    + "subtract-meter 'disk.free' did not resolve to any meter across 3 ticks",
                            "Peekaboot insights: series 'used' (meter 'disk.total'): "
                                    + "subtract-meter 'disk.free' did not resolve to any meter across 3 ticks");
        }
    }

    @Test
    void negativeDeltaYieldsNaNAndResetsBaseline() {
        Counter counter = registry.counter("hits");
        counter.increment(50);
        SeriesSampler sampler =
                new SeriesSampler(def("hits", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline 50
        registry.remove(counter);
        Counter fresh = registry.counter("hits");
        fresh.increment(3);
        assertThat(sampler.sample(10_000)).isNaN(); // 3 < 50 -> reset
        fresh.increment(10);
        assertThat(sampler.sample(10_000)).isCloseTo(1.0, within(1e-9)); // 10 in 10s
    }

    /** A negative count delta divided into a negative total delta is a plausible-looking wrong average, not a reset. */
    @Test
    void aTimerReRegisteredBehindTheBaselineYieldsNaNForAvgAndRebases() {
        Timer timer = registry.timer("req");
        for (int i = 0; i < 5; i++) {
            timer.record(Duration.ofMillis(100));
        }
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), Stat.AVG), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline: 5 calls, 500 ms
        registry.remove(timer);
        Timer fresh = registry.timer("req");
        fresh.record(Duration.ofMillis(30));
        assertThat(sampler.sample(10_000)).isNaN(); // -4 calls, -470 ms: nothing to average
        fresh.record(Duration.ofMillis(50));
        assertThat(sampler.sample(10_000)).isCloseTo(50.0, within(1e-9)); // one call of 50 ms since the rebase
    }

    /** A function may have counted long before its meter was registered, so its first reading starts no rate. */
    @Test
    void aFunctionCounterRegisteredLateOverAnExistingTotalStartsFromItsFirstReading() {
        SeriesSampler sampler =
                new SeriesSampler(def("jobs", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN(); // nothing to count yet
        total.set(5_000);
        FunctionCounter.builder("jobs", total, AtomicLong::get).register(registry);
        assertThat(sampler.sample(10_000)).isNaN(); // the 5000 were counted before it was registered
        total.set(5_030);
        assertThat(sampler.sample(10_000)).isEqualTo(3.0); // 30 in 10s
    }

    @Test
    void aFunctionTimerRegisteredLateAveragesOnlyWhatItRecordsAfterItsFirstReading() {
        SeriesSampler sampler =
                new SeriesSampler(def("calls", Map.of(), Stat.AVG), registry, UnresolvedMeterReport.WARN);
        assertThat(sampler.sample(10_000)).isNaN(); // nothing to average yet
        calls.count().set(10);
        calls.millis().set(5_000);
        FunctionTimer.builder(
                        "calls",
                        calls,
                        made -> made.count().get(),
                        made -> made.millis().get(),
                        TimeUnit.MILLISECONDS)
                .register(registry);
        assertThat(sampler.sample(10_000)).isNaN(); // the 10 calls were made before it was registered
        calls.count().set(12);
        calls.millis().set(5_300);
        assertThat(sampler.sample(10_000)).isEqualTo(150.0); // 2 calls in 300 ms
    }

    @Test
    void aFunctionCounterJoiningCountersAlreadySampledAddsNoSpike() {
        Counter a = Counter.builder("jobs").tag("queue", "a").register(registry);
        SeriesSampler sampler =
                new SeriesSampler(def("jobs", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline of a
        a.increment(10);
        total.set(1_000);
        FunctionCounter.builder("jobs", total, AtomicLong::get)
                .tag("queue", "b")
                .register(registry);
        assertThat(sampler.sample(10_000)).isEqualTo(1.0); // the 10 of a in 10s
        total.set(1_020);
        assertThat(sampler.sample(10_000)).isEqualTo(2.0); // the 20 of b in 10s
    }

    /** The other tag combinations kept counting, so a sum that fell with the departed one is no reset. */
    @Test
    void aMeterThatLeavesTheMatchIsNotReadAsAReset() {
        Counter a = Counter.builder("jobs").tag("queue", "a").register(registry);
        Counter b = Counter.builder("jobs").tag("queue", "b").register(registry);
        a.increment(100);
        b.increment(50);
        SeriesSampler sampler =
                new SeriesSampler(def("jobs", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baselines 100 and 50
        registry.remove(b);
        a.increment(10);
        assertThat(sampler.sample(10_000)).isEqualTo(1.0); // the 10 of a in 10s
    }

    /** A re-registered meter counts from 0 again, also while other tag combinations stayed. */
    @Test
    void aMeterBackAfterItLeftTheMatchCountsFromZero() {
        Counter.builder("jobs").tag("queue", "a").register(registry);
        Counter b = Counter.builder("jobs").tag("queue", "b").register(registry);
        b.increment(50);
        SeriesSampler sampler =
                new SeriesSampler(def("jobs", Map.of(), Stat.RATE), registry, UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline of b: 50
        registry.remove(b);
        sampler.sample(10_000); // b is gone
        Counter.builder("jobs").tag("queue", "b").register(registry).increment(20);
        assertThat(sampler.sample(10_000)).isEqualTo(2.0); // 20 in 10s, not a count behind 50
    }

    /** Micrometer runs an application's own function on every read, and one that throws must not reach the tick. */
    @Test
    void aMeterWhoseFunctionThrowsReadsAsAGap() {
        SeriesSampler sampler = rateOverBrokenCounter();
        failing.set(true);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            assertThat(sampler.sample(10_000)).isNaN();

            assertThat(capture.appender().list)
                    .extracting(event -> event.getLevel(), event -> event.getFormattedMessage())
                    .containsExactly(tuple(Level.WARN, FAILED_READ_OF_BROKEN));
        }
    }

    /** Each outage is its own problem: a meter that read in between is reported again when it fails again. */
    @Test
    void aFailingMeterIsReportedOncePerOutage() {
        SeriesSampler sampler = rateOverBrokenCounter();

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            failing.set(true);
            for (int tick = 0; tick < 3; tick++) {
                sampler.sample(10_000);
            }
            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage()).isEqualTo(FAILED_READ_OF_BROKEN);
                assertThat(event.getThrowableProxy().getClassName()).isEqualTo(IllegalStateException.class.getName());
            });

            failing.set(false);
            sampler.sample(10_000);
            failing.set(true);
            sampler.sample(10_000);

            assertThat(capture.appender().list).hasSize(2);
        }
    }

    /** A rate measured across a failed read would divide the counts of two intervals by one. */
    @Test
    void aRateAfterAFailedReadStartsFromAFreshBaseline() {
        SeriesSampler sampler = rateOverBrokenCounter();

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            total.set(10);
            assertThat(sampler.sample(10_000)).isNaN(); // first sample: no baseline
            failing.set(true);
            assertThat(sampler.sample(10_000)).isNaN(); // the read failed
            failing.set(false);
            total.set(70);
            assertThat(sampler.sample(10_000)).isNaN(); // no baseline again: the 60 took two intervals
            total.set(100);
            assertThat(sampler.sample(10_000)).isEqualTo(3.0); // 30 in 10s

            assertThat(capture.appender().list).hasSize(1);
        }
    }

    /** A counter beside the failed meter kept counting through the gap, so its count has no known start either. */
    @Test
    void aCounterBesideAMeterWhoseReadFailedStartsFromAFreshBaselineToo() {
        SeriesSampler sampler = rateOverBrokenCounter();
        Counter beside = Counter.builder("broken").tag("kind", "counted").register(registry);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            beside.increment(100);
            assertThat(sampler.sample(10_000)).isNaN(); // first sample: no baseline
            failing.set(true);
            assertThat(sampler.sample(10_000)).isNaN(); // the read failed
            failing.set(false);
            beside.increment(60);
            assertThat(sampler.sample(10_000)).isNaN(); // no baseline again: the 60 took two intervals
            beside.increment(30);
            assertThat(sampler.sample(10_000)).isEqualTo(3.0); // 30 in 10s

            assertThat(capture.appender().list).hasSize(1);
        }
    }

    @Test
    void aSeriesReadsTheFirstPresentAlternative() {
        Gauge.builder("b", () -> 7).register(registry);
        SeriesSampler sampler = new SeriesSampler(
                def("a", Map.of(), Stat.VALUE, new MeterAlternative("b", Map.of())),
                registry,
                UnresolvedMeterReport.WARN);

        assertThat(sampler.sample(10_000)).isEqualTo(7.0);
    }

    @Test
    void theFirstPresentCandidateWinsOverALaterOne() {
        Counter a = registry.counter("a");
        Counter b = registry.counter("b");
        SeriesSampler sampler = new SeriesSampler(
                def("a", Map.of(), Stat.RATE, new MeterAlternative("b", Map.of())),
                registry,
                UnresolvedMeterReport.WARN);
        sampler.sample(10_000); // baseline
        a.increment(20);
        b.increment(300);

        assertThat(sampler.sample(10_000)).isCloseTo(2.0, within(1e-9)); // a's 20 in 10s
    }

    /** Micrometer registers the 5xx timer on the first 5xx, and until then the series must not move to another name. */
    @Test
    void aTagValueMatchingNothingYetStaysOnThePresentCandidate() {
        Timer.builder("a").tag("outcome", "SUCCESS").register(registry);
        Timer otherName = Timer.builder("b").tag("outcome", "SERVER_ERROR").register(registry);
        SeriesSampler sampler = new SeriesSampler(
                def(
                        "a",
                        Map.of("outcome", "SERVER_ERROR"),
                        Stat.RATE,
                        new MeterAlternative("b", Map.of("outcome", "SERVER_ERROR"))),
                registry,
                UnresolvedMeterReport.WARN);
        SeriesSampler control = new SeriesSampler(
                def("a", Map.of("outcome", "SERVER_ERROR"), Stat.RATE), registry, UnresolvedMeterReport.NONE);

        assertReadsAlike(sampler, control);
        otherName.record(Duration.ofMillis(5));
        assertReadsAlike(sampler, control);
        Timer.builder("a").tag("outcome", "SERVER_ERROR").register(registry).record(Duration.ofMillis(5));
        double firstServerErrors = control.sample(10_000);
        assertThat(firstServerErrors).isCloseTo(0.1, within(1e-9));
        assertThat(sampler.sample(10_000)).isEqualTo(firstServerErrors);
    }

    private static void assertReadsAlike(SeriesSampler sampler, SeriesSampler control) {
        // a gap is a reading too, and AssertJ's equality refuses NaN == NaN
        assertThat(sampler.sample(10_000)).usingComparator(Double::compare).isEqualTo(control.sample(10_000));
    }

    /** Under OpenTelemetry conventions the heap meters keep their name and carry a different tag key. */
    @Test
    void aCandidateWhoseTagKeyNoMeterCarriesIsSkipped() {
        Gauge.builder("m", () -> 5).tag("other", "heap").register(registry);
        SeriesSampler sampler = new SeriesSampler(
                def("m", Map.of("area", "heap"), Stat.VALUE, new MeterAlternative("m", Map.of("other", "heap"))),
                registry,
                UnresolvedMeterReport.WARN);

        assertThat(sampler.sample(10_000)).isEqualTo(5.0);
    }

    @Test
    void noPresentCandidateIsReportedAsUnresolved() {
        SeriesSampler sampler = new SeriesSampler(
                def("a", Map.of(), Stat.VALUE, new MeterAlternative("b", Map.of())),
                registry,
                UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            sampler.sample(10_000);
            sampler.sample(10_000);
            assertThat(capture.appender().list).isEmpty();
            sampler.sample(10_000); // 3rd consecutive unresolved tick: threshold reached

            assertThat(capture.appender().list).singleElement().satisfies(event -> {
                assertThat(event.getLevel()).isEqualTo(Level.WARN);
                assertThat(event.getFormattedMessage())
                        .isEqualTo("Peekaboot insights: series 's': meter 'a' or 'b' did not resolve to any meter"
                                + " across 3 ticks");
            });
        }
    }

    @Test
    void aFailedReadNamesEveryCandidate() {
        brokenCounter("b");
        SeriesSampler sampler = new SeriesSampler(
                def("a", Map.of(), Stat.RATE, new MeterAlternative("b", Map.of())),
                registry,
                UnresolvedMeterReport.WARN);
        failing.set(true);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            assertThat(sampler.sample(10_000)).isNaN();

            assertThat(capture.appender().list)
                    .extracting(event -> event.getFormattedMessage())
                    .containsExactly("Peekaboot insights: series 's': reading meter 'a' or 'b' failed; recording a"
                            + " gap until it reads again");
        }
    }

    @Test
    void anUnresolvedSubtractMeterNamesEveryCandidate() {
        Gauge.builder("b", () -> 1000).register(registry);
        SeriesSampler sampler = new SeriesSampler(
                new SeriesDef(
                        "s",
                        "S",
                        "a",
                        Map.of(),
                        Stat.VALUE,
                        "free",
                        null,
                        List.of(new MeterAlternative("b", Map.of()))),
                registry,
                UnresolvedMeterReport.WARN);

        try (LogCapture capture = LogCapture.attach(SeriesSampler.class)) {
            for (int tick = 0; tick < 3; tick++) {
                assertThat(sampler.sample(10_000)).isNaN();
            }

            assertThat(capture.appender().list)
                    .extracting(event -> event.getFormattedMessage())
                    .containsExactly("Peekaboot insights: series 's' (meter 'a' or 'b'): subtract-meter 'free' did"
                            + " not resolve to any meter across 3 ticks");
        }
    }
}
