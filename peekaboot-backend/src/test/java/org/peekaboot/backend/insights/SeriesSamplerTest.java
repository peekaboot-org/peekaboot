package org.peekaboot.backend.insights;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.LongTaskTimer;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.peekaboot.backend.insights.config.SeriesDef;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class SeriesSamplerTest {

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
    }

    private static SeriesDef def(String meter, Map<String, String> tags, String stat) {
        return new SeriesDef("s", "S", meter, tags, stat, null, null);
    }

    @Test
    void unresolvedMeterYieldsNaN() {
        SeriesSampler sampler = new SeriesSampler(def("does.not.exist", Map.of(), "value"), registry);
        assertThat(sampler.sample(10_000)).isNaN();
    }

    @Test
    void resolvesMetersRegisteredAfterConstruction() {
        SeriesSampler sampler = new SeriesSampler(def("late.gauge", Map.of(), "value"), registry);
        assertThat(sampler.sample(10_000)).isNaN();
        AtomicLong value = registry.gauge("late.gauge", new AtomicLong(42));
        assertThat(sampler.sample(10_000)).isEqualTo(42.0);
    }

    @Test
    void sumsGaugesAcrossTagCombinations() {
        registry.gauge("mem", io.micrometer.core.instrument.Tags.of("area", "heap", "id", "eden"), new AtomicLong(100));
        registry.gauge("mem", io.micrometer.core.instrument.Tags.of("area", "heap", "id", "old"), new AtomicLong(200));
        registry.gauge("mem", io.micrometer.core.instrument.Tags.of("area", "nonheap", "id", "meta"), new AtomicLong(999));
        SeriesSampler sampler = new SeriesSampler(def("mem", Map.of("area", "heap"), "value"), registry);
        assertThat(sampler.sample(10_000)).isEqualTo(300.0);
    }

    @Test
    void counterRateIsDeltaPerSecond() {
        Counter counter = registry.counter("hits");
        SeriesSampler sampler = new SeriesSampler(def("hits", Map.of(), "rate"), registry);
        counter.increment(5);
        assertThat(sampler.sample(10_000)).isNaN(); // first sample: no baseline
        counter.increment(20);
        assertThat(sampler.sample(10_000)).isCloseTo(2.0, within(1e-9)); // 20 in 10s
    }

    @Test
    void timerAvgIsDeltaTotalOverDeltaCountInMillis() {
        Timer timer = registry.timer("req");
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), "avg"), registry);
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
        SeriesSampler sampler = new SeriesSampler(def("req", Map.of(), "max"), registry);
        assertThat(sampler.sample(10_000)).isCloseTo(250.0, within(1e-9));
    }

    @Test
    void longTaskTimerValueIsActiveTasks() {
        LongTaskTimer ltt = LongTaskTimer.builder("inflight").register(registry);
        LongTaskTimer.Sample running = ltt.start();
        SeriesSampler sampler = new SeriesSampler(def("inflight", Map.of(), "value"), registry);
        assertThat(sampler.sample(10_000)).isEqualTo(1.0);
        running.stop();
        assertThat(sampler.sample(10_000)).isEqualTo(0.0);
    }

    @Test
    void subtractMeterComputesDifference() {
        registry.gauge("disk.total", new AtomicLong(1000));
        registry.gauge("disk.free", new AtomicLong(400));
        SeriesDef diff = new SeriesDef("used", "Used", "disk.total", Map.of(), "value", "disk.free", null);
        SeriesSampler sampler = new SeriesSampler(diff, registry);
        assertThat(sampler.sample(10_000)).isEqualTo(600.0);
    }

    @Test
    void negativeDeltaYieldsNaNAndResetsBaseline() {
        Counter counter = registry.counter("hits");
        counter.increment(50);
        SeriesSampler sampler = new SeriesSampler(def("hits", Map.of(), "rate"), registry);
        sampler.sample(10_000); // baseline 50
        registry.remove(counter);
        Counter fresh = registry.counter("hits");
        fresh.increment(3);
        assertThat(sampler.sample(10_000)).isNaN(); // 3 < 50 -> reset
        fresh.increment(10);
        assertThat(sampler.sample(10_000)).isCloseTo(1.0, within(1e-9)); // 10 in 10s
    }
}
